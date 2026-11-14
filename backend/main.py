from datetime import date, datetime, timedelta, timezone
import base64
import logging
import os
import secrets
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

try:  # Support both "uvicorn backend.main:app" and "cd backend && python main.py"
    from . import storage
except ImportError:  # pragma: no cover - direct script execution
    import storage

# Community fitness pact: import the shared rules loader (pact/rules.py) from
# the repository root, so it works whether the server is started from the
# repo root or from inside backend/.
_REPO_ROOT = str(Path(__file__).resolve().parent.parent)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from pact.rules import PactRules, load_rules, canonical_rules as pact_canonical_rules, rules_hash as pact_rules_hash  # noqa: E402
from pact import attestation as pact_attestation  # noqa: E402
from pact.eligibility import (  # noqa: E402
    Flag as EvalFlag,
    HealthRecord,
    community_summary,
    current_week_start,
    evaluate_week,
    resolve_boundary_timezone,
    run_pipeline,
    validate_timezone,
)

try:  # same dual-mode import as storage, but only after the pact bootstrap
    from . import pact_store
except ImportError:  # pragma: no cover - direct script execution
    import pact_store

try:  # NEAR sign-in modules, same dual-mode import as storage
    from . import auth_store, near_auth
except ImportError:  # pragma: no cover - direct script execution
    import auth_store
    import near_auth

try:  # DAO + reputation router
    from . import dao_router as _dao_router
except ImportError:  # pragma: no cover
    import dao_router as _dao_router  # type: ignore

try:  # Sandbox router for hackathon judge self-service
    from . import sandbox_router as _sandbox_router
except ImportError:  # pragma: no cover
    import sandbox_router as _sandbox_router  # type: ignore

try:  # Dashboard snapshot router for high-performance cached client reads
    from . import dashboard_router as _dashboard_router
except ImportError:  # pragma: no cover
    import dashboard_router as _dashboard_router  # type: ignore

try:  # Contract client for challenge-driven attestations
    from . import contract_client as _contract_client
except ImportError:  # pragma: no cover
    import contract_client as _contract_client  # type: ignore

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("health_sync_backend")

app = FastAPI(
    title="Health Connect Sync Backend",
    version="1.3.0",
    description=(
        "Health Connect sync backend with persistent storage, retrieval "
        "endpoints, a browser dashboard, NEAR-account sign-in (NEP-413), "
        "the community fitness pact, and DAO governance endpoints."
    ),
)

# Enable CORS for local testing from any origin or web client
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def pact_profile_echo_middleware(request: Request, call_next):
    profile_val = (
        request.headers.get("X-Pact-Profile")
        or request.headers.get("X-Pact-Contract")
        or request.query_params.get("profile")
        or request.query_params.get("contract_id")
    )
    if profile_val:
        try:
            prof = get_profile(profile_val)
        except ValueError as exc:
            return JSONResponse(
                status_code=400,
                content={"detail": str(exc)},
            )
    else:
        prof = get_profile("real")

    response = await call_next(request)
    response.headers["X-Pact-Profile"] = prof.name
    response.headers["X-Pact-Contract-Id"] = prof.contract_id
    response.headers["X-Pact-Contract"] = prof.contract_id
    return response


# Mount DAO + reputation router (Sputnik logic lives in dao_router.py,
# not in main.py — per the separation-of-concerns requirement).
app.include_router(_dao_router.router)
app.include_router(_sandbox_router.router)
app.include_router(_dashboard_router.router)

# If this environment variable is set, all sync/retrieval API calls must send
# a matching X-API-Key header. Leave it unset for open local testing.
EXPECTED_API_KEY = os.getenv("HEALTH_SYNC_API_KEY", "").strip()


def verify_api_key(
    x_api_key: Optional[str] = Header(None, alias="X-API-Key"),
) -> None:
    """Optionally enforce X-API-Key when HEALTH_SYNC_API_KEY is configured."""
    if EXPECTED_API_KEY and x_api_key != EXPECTED_API_KEY:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing X-API-Key header",
        )


ADMIN_API_KEY = os.getenv("HEALTH_SYNC_ADMIN_KEY", os.getenv("HEALTH_SYNC_API_KEY", "")).strip()


def verify_admin_access(
    x_admin_key: Optional[str] = Header(None, alias="X-Admin-Key"),
    x_api_key: Optional[str] = Header(None, alias="X-API-Key"),
    authorization: Optional[str] = Header(None),
) -> None:
    """Admin-only protection for /api/admin/* endpoints.

    Requires an administrative API key via X-Admin-Key or X-API-Key header.
    Normal user session tokens (Authorization: Bearer <session>) are explicitly
    rejected with HTTP 403 Forbidden because user credentials must never grant
    unrestricted administrative inspection across all members' data.
    """
    if authorization and authorization.lower().startswith("bearer "):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin access forbidden: user session tokens cannot access admin endpoints",
        )
    key = x_admin_key or x_api_key
    effective_admin_key = ADMIN_API_KEY or EXPECTED_API_KEY
    if effective_admin_key and key != effective_admin_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing admin API key (expected X-Admin-Key or X-API-Key)",
        )


from pact.profiles import (
    PactProfile,
    get_profile,
    ALLOWLISTED_CONTRACT_IDS,
    is_valid_profile_or_contract,
)


def resolve_pact_profile(
    x_pact_profile: Optional[str] = Header(None, alias="X-Pact-Profile"),
    x_pact_contract: Optional[str] = Header(None, alias="X-Pact-Contract"),
    profile: Optional[str] = Query(None),
    contract_id: Optional[str] = Query(None),
) -> PactProfile:
    val = profile or contract_id or x_pact_profile or x_pact_contract or "real"
    try:
        return get_profile(val)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(exc),
        )


# ---------------------------------------------------------------------------
# NEAR sign-in (identity). There is no email/password: the member IS a NEAR
# account. Flow (NEP-413, see backend/near_auth.py):
#   POST /api/auth/nonce  {accountId}        -> challenge (nonce/message/recipient)
#   client signs the challenge with the account's Full Access Key
#   POST /api/auth/verify {accountId, publicKey, signature, nonce}
#        -> Ed25519 verification + RPC key check + single-use nonce
#        -> session token
# ---------------------------------------------------------------------------

# The NEP-413 "recipient": identifies THIS backend to wallets. Set it to
# your real domain in production (it is part of the signed challenge).
AUTH_DOMAIN = (
    os.getenv("HEALTH_SYNC_AUTH_DOMAIN", "opaque-pact").strip() or "opaque-pact"
)

# Which pact contract attestations are settled on (attestation payload field).
PACT_CONTRACT_ID = (
    os.getenv("PACT_CONTRACT_ID", "opaque-pact.testnet").strip()
    or "opaque-pact.testnet"
)


class NearNonceRequest(BaseModel):
    accountId: str = Field(
        min_length=2, max_length=64, description="NEAR account id to sign in as"
    )


class NearNonceResponse(BaseModel):
    accountId: str
    nonce: str = Field(description="base64 of the 32-byte NEP-413 challenge nonce")
    message: str = Field(description="exact NEP-413 message to sign")
    recipient: str = Field(description="exact NEP-413 recipient to sign for")
    expiresAt: str


class NearVerifyRequest(BaseModel):
    accountId: str
    publicKey: str = Field(description='"ed25519:<base58>" Full Access Key used')
    signature: str = Field(description="base64 of the 64-byte Ed25519 signature")
    nonce: str = Field(description="the base64 nonce from /api/auth/nonce")


class NearSessionResponse(BaseModel):
    token: str = Field(description="bearer token for Authorization headers")
    accountId: str
    publicKey: str
    expiresAt: str


@app.post("/api/auth/nonce", response_model=NearNonceResponse)
@app.post("/auth/nonce", response_model=NearNonceResponse, include_in_schema=False)
def request_near_nonce(payload: NearNonceRequest):
    """Issue a single-use NEP-413 sign-in challenge for one account."""
    try:
        near_auth.validate_account_id(payload.accountId)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    nonce = secrets.token_bytes(32)
    row = auth_store.issue_nonce(
        nonce, payload.accountId, near_auth.SIGN_IN_MESSAGE, AUTH_DOMAIN
    )
    return NearNonceResponse(
        accountId=payload.accountId,
        nonce=base64.b64encode(nonce).decode("ascii"),
        message=near_auth.SIGN_IN_MESSAGE,
        recipient=AUTH_DOMAIN,
        expiresAt=datetime.fromtimestamp(row["expires_at"], tz=timezone.utc).isoformat(),
    )


@app.post("/api/auth/verify", response_model=NearSessionResponse)
@app.post("/auth/verify", response_model=NearSessionResponse, include_in_schema=False)
def verify_near_signin(payload: NearVerifyRequest):
    """Verify a NEP-413 signature and issue a session token.

    Checks, in order (all must pass):
    1. the nonce is known, unused and unexpired, and was issued for this
       account (replay + cross-account protection);
    2. the Ed25519 signature verifies against the claimed public key over
       the exact NEP-413 challenge (tamper protection);
    3. the key really is a Full Access Key of the account on chain (NEP-413
       requirement — checked against the RPC node, not client claims);
    4. the nonce is consumed atomically (a second verify with the same
       nonce+signature cannot succeed — replay protection).
    """
    try:
        near_auth.validate_account_id(payload.accountId)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))

    try:
        public_key_bytes = near_auth.parse_public_key(payload.publicKey)
        nonce_bytes = base64.b64decode(payload.nonce, validate=True)
        signature = base64.b64decode(payload.signature, validate=True)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"malformed publicKey/nonce/signature: {exc}",
        )
    if len(nonce_bytes) != 32 or len(signature) != 64:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="nonce must decode to 32 bytes and signature to 64 bytes",
        )

    # 1. nonce freshness + account binding
    nonce_row = auth_store.peek_nonce(nonce_bytes)
    if nonce_row is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="unknown, expired or already-used nonce",
        )
    if nonce_row["account_id"] != payload.accountId:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="nonce was issued for a different account",
        )

    # 2. signature over the exact challenge
    if not near_auth.verify_nep413_signature(
        public_key_bytes,
        signature,
        nonce_row["message"],
        nonce_bytes,
        nonce_row["recipient"],
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="NEP-413 signature verification failed",
        )

    # 3. on-chain Full Access Key check (tests substitute near_auth.fetch_access_keys)
    try:
        keys = near_auth.fetch_access_keys(payload.accountId)
    except near_auth.AccountNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)
        )
    except near_auth.NearRpcError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"NEAR RPC unavailable: {exc}",
        )
    if not near_auth.is_full_access_key(keys, payload.publicKey):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=(
                "public key is not a Full Access Key of this account "
                "(NEP-413 requires a Full Access Key)"
            ),
        )

    # 4. single-use: consume atomically, then issue the session
    if auth_store.consume_nonce(nonce_bytes) is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="nonce was already used (replay rejected)",
        )
    session = auth_store.create_session(payload.accountId, payload.publicKey)
    logger.info("NEAR sign-in ok for %s", payload.accountId)
    return NearSessionResponse(**session)


def _resolve_account(authorization: Optional[str]) -> Optional[str]:
    """Shared core: Bearer token -> account id, or None if no header sent.

    An absent header means 'not authenticated' (callers decide whether that
    is allowed); a present-but-invalid one is a 401.
    """
    if not authorization:
        return None
    scheme, _, token = authorization.partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authorization header must be 'Bearer <token>'",
        )
    session = auth_store.get_session(token)
    if session is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid or expired session; sign in via /api/auth/verify",
        )
    return session["accountId"]


def require_account(authorization: Optional[str] = Header(None)) -> str:
    """FastAPI dependency: the caller's NEAR account id (session required)."""
    account_id = _resolve_account(authorization)
    if account_id is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="this endpoint requires a session; sign in via /api/auth/verify",
        )
    return account_id


def optional_account(authorization: Optional[str] = Header(None)) -> Optional[str]:
    """FastAPI dependency: account id when a Bearer token is presented, else
    None (legacy X-API-Key / dashboard view). Invalid tokens still 401."""
    return _resolve_account(authorization)


class RecordPayload(BaseModel):
    recordType: str
    startTime: str
    endTime: Optional[str] = None
    recordData: Dict[str, Any]
    metadata: Optional[Dict[str, Any]] = None


class SyncPayload(BaseModel):
    deviceId: str = Field(..., description="Unique client device identifier")
    syncTimestamp: str = Field(
        ..., description="ISO 8601 timestamp when sync was triggered"
    )
    timezone: Optional[str] = Field(
        default=None,
        description="Member's fixed IANA day-boundary timezone (e.g. Europe/Warsaw)",
    )
    summary: Optional[Dict[str, int]] = Field(
        default=None, description="Summary counts per record type"
    )
    records: List[RecordPayload] = Field(
        default_factory=list, description="Array of health records"
    )


class SyncResponse(BaseModel):
    status: str
    message: str
    receivedRecordsCount: int
    summary: Dict[str, int]
    deviceId: str
    accountId: str = Field(description="NEAR account the upload is attributed to")
    serverTimestamp: str
    uploadId: str
    retrievalUrl: str


class UploadSummary(BaseModel):
    uploadId: str
    deviceId: str
    accountId: Optional[str] = Field(
        default=None,
        description="NEAR account the upload is attributed to (None for "
        "legacy pre-sign-in rows)",
    )
    syncTimestamp: str
    receivedAt: str
    recordCount: int
    summary: Dict[str, int]
    retrievalUrl: str


class UploadListResponse(BaseModel):
    count: int
    limit: int
    offset: int
    uploads: List[UploadSummary]


class UploadDetailResponse(UploadSummary):
    payload: Dict[str, Any]


class RecordEntry(BaseModel):
    uploadId: str
    deviceId: str
    receivedAt: str
    recordType: str
    startTime: str
    endTime: Optional[str] = None
    recordData: Dict[str, Any]
    metadata: Optional[Dict[str, Any]] = None


class RecordListResponse(BaseModel):
    count: int
    limit: int
    offset: int
    records: List[RecordEntry] = Field(default_factory=list)


class StatsResponse(BaseModel):
    uploads: int
    records: int
    devices: int
    byRecordType: Dict[str, int]
    latestReceivedAt: Optional[str] = None


class DeleteResponse(BaseModel):
    status: str
    deleted: bool
    uploadId: Optional[str] = None
    deletedCount: Optional[int] = None


@app.get("/")
def root():
    return {
        "service": "Health Connect Sync Backend",
        "status": "online",
        "syncEndpoint": "/api/health/sync",
        "dashboard": "/dashboard",
        "uploadsEndpoint": "/api/health/uploads",
        "recordsEndpoint": "/api/health/records",
        "statsEndpoint": "/api/health/stats",
        "authNonceEndpoint": "/api/auth/nonce",
        "authVerifyEndpoint": "/api/auth/verify",
        "authNonceAlias": "/auth/nonce",
        "authVerifyAlias": "/auth/verify",
        "pactRulesEndpoint": "/api/pact/rules",
        "pactEligibilityEndpoint": "/api/pact/eligibility",
        "pactCommunityEndpoint": "/api/pact/community",
        "pactAttestationKeyEndpoint": "/api/pact/attestation-key",
        "pactAttestationEndpoint": "/api/pact/attestation/{week}",
        "attestationKeyAlias": "/attestation-key",
        "attestationAlias": "/attestation/{challenge_id}",
        "daoProposalsEndpoint": "/api/dao/proposals",
        "pactChallengeEndpoint": "/api/pact/challenge",
        "pactReputationEndpoint": "/api/pact/reputation/{account_id}",
    }


@app.get("/api/health/status")
def health_status():
    return {
        "status": "ok",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/health", include_in_schema=False)
@app.get("/healthz", include_in_schema=False)
def health_alias():
    """Common health-check aliases for tunnels and hosting platforms."""
    return health_status()


@app.post(
    "/api/health/sync",
    response_model=SyncResponse,
    status_code=status.HTTP_200_OK,
    dependencies=[Depends(verify_api_key)],
)
async def sync_health_data(
    payload: SyncPayload, account_id: str = Depends(require_account)
):
    """Store one health data batch, attributed to the signed-in account.

    A session is REQUIRED: uploads without an Authorization: Bearer header
    are rejected (health data must be attributable to its NEAR account).
    """
    if payload.timezone:
        validate_timezone(payload.timezone)
        existing = pact_store.get_member(account_id)
        if existing is not None:
            if existing["timezone"] != payload.timezone:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=(
                        f"member timezone is locked to '{existing['timezone']}' "
                        f"and cannot be changed during an active challenge"
                    ),
                )
        else:
            active_ch = pact_store.get_active_challenge()
            ch_id = int(active_ch["challengeId"]) if active_ch else 0
            pact_store.upsert_member(account_id, ch_id, payload.timezone)

    summary_counts: Dict[str, int] = {}
    for record in payload.records:
        summary_counts[record.recordType] = (
            summary_counts.get(record.recordType, 0) + 1
        )

    total_records = len(payload.records)
    stored = storage.save_upload(payload.model_dump(), account_id=account_id)

    logger.info(
        "Stored upload %s from device %s of account %s: %d records %s",
        stored["uploadId"],
        payload.deviceId,
        account_id,
        total_records,
        summary_counts,
    )

    return SyncResponse(
        status="success",
        message=f"Successfully received {total_records} health records",
        receivedRecordsCount=total_records,
        summary=summary_counts,
        deviceId=payload.deviceId,
        accountId=account_id,
        serverTimestamp=stored["receivedAt"],
        uploadId=stored["uploadId"],
        retrievalUrl=stored["retrievalUrl"],
    )


@app.get(
    "/api/health/uploads",
    response_model=UploadListResponse,
    dependencies=[Depends(verify_api_key)],
)
def get_uploads(
    device_id: Optional[str] = Query(
        None, alias="deviceId", description="Filter by device ID"
    ),
    record_type: Optional[str] = Query(
        None, alias="recordType", description="Only uploads containing this type"
    ),
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
    account_id: str = Depends(require_account),
):
    """List stored uploads for the authenticated caller only, newest first.

    Requires an active session via /api/auth/verify. Unauthenticated listing is forbidden.
    """
    return storage.list_uploads(
        device_id=device_id,
        record_type=record_type,
        limit=limit,
        offset=offset,
        account_id=account_id,
    )


@app.get(
    "/api/health/uploads/{upload_id}",
    response_model=UploadDetailResponse,
    dependencies=[Depends(verify_api_key)],
)
def get_upload(
    upload_id: str,
    account_id: str = Depends(require_account),
):
    """Return one upload with its complete original JSON payload for the caller.

    Requires an active session. Another member's upload id returns 404.
    """
    upload = storage.get_upload(upload_id, account_id=account_id)
    if upload is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Upload '{upload_id}' was not found",
        )
    return upload


@app.delete(
    "/api/health/uploads/{upload_id}",
    response_model=DeleteResponse,
    dependencies=[Depends(verify_api_key)],
)
def delete_upload(
    upload_id: str,
    account_id: str = Depends(require_account),
):
    """Delete a single stored upload (scoped to the authenticated caller)."""
    if not storage.delete_upload(upload_id, account_id=account_id):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Upload '{upload_id}' was not found",
        )
    return DeleteResponse(status="success", deleted=True, uploadId=upload_id)


@app.delete(
    "/api/health/uploads",
    response_model=DeleteResponse,
    dependencies=[Depends(verify_api_key)],
)
def clear_uploads(
    confirm: bool = Query(
        False, description="Must be true to delete every stored upload"
    ),
    account_id: str = Depends(require_account),
):
    """Delete stored uploads for the authenticated caller. Requires ?confirm=true."""
    if not confirm:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Pass ?confirm=true to delete every stored upload",
        )
    deleted_count = storage.clear_uploads(account_id=account_id)
    logger.info(
        "Deleted %d stored upload(s) for %s",
        deleted_count,
        account_id,
    )
    return DeleteResponse(
        status="success",
        deleted=True,
        message=f"Deleted {deleted_count} upload(s)",
        deletedCount=deleted_count,
    )


# ---------------------------------------------------------------------------
# Admin uploads endpoints (separate path for internal testing / dashboard;
# never called by the app)
# ---------------------------------------------------------------------------

@app.get(
    "/api/admin/health/uploads",
    response_model=UploadListResponse,
    dependencies=[Depends(verify_admin_access)],
)
def admin_get_uploads(
    device_id: Optional[str] = Query(None, alias="deviceId"),
    record_type: Optional[str] = Query(None, alias="recordType"),
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    """Admin-only listing of all uploads across all accounts."""
    return storage.list_uploads(
        device_id=device_id,
        record_type=record_type,
        limit=limit,
        offset=offset,
        account_id=None,
    )


@app.get(
    "/api/admin/health/uploads/{upload_id}",
    response_model=UploadDetailResponse,
    dependencies=[Depends(verify_admin_access)],
)
def admin_get_upload(upload_id: str):
    """Admin-only inspection of an upload."""
    upload = storage.get_upload(upload_id, account_id=None)
    if upload is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Upload '{upload_id}' was not found",
        )
    return upload


@app.get(
    "/api/health/records",
    response_model=RecordListResponse,
    dependencies=[Depends(verify_api_key)],
)
def get_records(
    device_id: Optional[str] = Query(
        None, alias="deviceId", description="Filter by device ID"
    ),
    record_type: Optional[str] = Query(
        None, alias="recordType", description="Filter by record type"
    ),
    upload_id: Optional[str] = Query(
        None, alias="uploadId", description="Filter by upload ID"
    ),
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    account_id: Optional[str] = Depends(optional_account),
):
    """Return a flattened list of every stored health record.

    With a Bearer session only the caller's own records are visible.
    """
    return storage.list_records(
        device_id=device_id,
        record_type=record_type,
        upload_id=upload_id,
        limit=limit,
        offset=offset,
        account_id=account_id,
    )


@app.get(
    "/api/health/stats",
    response_model=StatsResponse,
    dependencies=[Depends(verify_api_key)],
)
def get_stats(account_id: Optional[str] = Depends(optional_account)):
    """Return aggregate upload/record counts for the dashboard.

    With a Bearer session the aggregates cover only the caller's uploads.
    """
    return storage.get_stats(account_id=account_id)


@app.get("/api/pact/rules", response_model=PactRules)
def get_pact_rules(prof: PactProfile = Depends(resolve_pact_profile)) -> PactRules:
    """Read-only view of the community fitness pact rules (pact/rules.json).

    Returns the validated rules WITH rules_hash in the JSON body so callers
    can compare it against the contract's get_rules_hash() view directly.
    The hash is also in the X-Pact-Rules-Hash header for convenience.

    rules_hash is computed from the immutable on-chain economic fields only;
    see pact/rules.py::canonical_rules(). The param views are the source of
    truth; the hash is a commitment label.
    """
    from fastapi.responses import JSONResponse
    r = load_rules()
    body = r.model_dump()
    if prof.is_demo:
        body["submission_grace_secs"] = prof.submission_grace_secs
    h = prof.rules_hash
    body["rules_hash"] = h
    response = JSONResponse(content=body)
    response.headers["X-Pact-Rules-Hash"] = h
    response.headers["X-Pact-Profile"] = prof.name
    response.headers["X-Pact-Contract"] = prof.contract_id
    response.headers["X-Pact-Contract-Id"] = prof.contract_id
    return response


# ---------------------------------------------------------------------------
# Community fitness pact: deterministic eligibility pipeline endpoints.
#
# Privacy contract (tested in backend/test_pact.py):
# - POST /api/pact/eligibility ingests ONE member's raw records and returns
#   only that member's own verdict. Raw records are never echoed back.
# - GET  /api/pact/community returns ONLY community aggregates (enrolled,
#   on-track %, days left). No member ids, no per-member steps, no flags.
# ---------------------------------------------------------------------------
class PactEligibilityRequest(BaseModel):
    timezone: Optional[str] = Field(
        default=None,
        description=(
            "Member's fixed IANA day-boundary timezone. Locked at first "
            "ingest; later requests with a different zone are rejected."
        ),
    )
    weekStart: Optional[date] = Field(
        default=None,
        description="Challenge week start (must fall on rules.week_start_day). "
        "Defaults to the current week in the member's day-boundary timezone.",
    )
    records: List[HealthRecord] = Field(default_factory=list)


class PactEligibilityResponse(BaseModel):
    memberId: str
    challengeId: int
    weekStart: date
    daysCompleted: int
    passed: bool
    flags: List[EvalFlag]


class PactCommunityResponse(BaseModel):
    challengeId: int
    weekStart: date
    weekEnd: date
    enrolled: int
    onTrackPct: int
    daysLeft: int


@app.post(
    "/api/pact/eligibility",
    response_model=PactEligibilityResponse,
    status_code=status.HTTP_200_OK,
    dependencies=[Depends(verify_api_key)],
)
async def ingest_pact_eligibility(
    payload: PactEligibilityRequest,
    account_id: str = Depends(require_account),
    prof: PactProfile = Depends(resolve_pact_profile),
):
    """Run the deterministic pipeline over one member's raw Health Connect
    records and store the derived daily/weekly results.

    The member IS the signed-in NEAR account (member_id = account_id from
    the session; clients cannot claim another member's id). The response is
    that member's own verdict only — no other member's data is ever
    reachable from this endpoint.
    """
    rules = load_rules()  # re-validated every call; fails loudly
    member_id = account_id

    existing = pact_store.get_member(member_id)
    if existing is not None:
        member_tz = existing["timezone"]
        if payload.timezone is not None and payload.timezone != member_tz:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    f"member timezone is locked to '{member_tz}' "
                    "(set at first ingest for deterministic day boundaries)"
                ),
            )
    else:
        member_tz = payload.timezone or "UTC"
        try:
            validate_timezone(member_tz)
        except ValueError as exc:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)
            )

    boundary_tz = resolve_boundary_timezone(rules, member_tz)
    now = datetime.now(timezone.utc)

    if payload.weekStart is not None:
        week_start = payload.weekStart
        if week_start.isoweekday() != rules.week_start_day:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    f"weekStart {week_start} must be a "
                    f"{rules.week_start_day_name} (rules.week_start_day="
                    f"{rules.week_start_day})"
                ),
            )
    else:
        week_start = current_week_start(now, rules, boundary_tz)

    challenge = pact_store.get_or_create_challenge(
        week_start.isoformat(), rules, contract_id=prof.contract_id
    )
    pact_store.upsert_member(member_id, challenge["challengeId"], member_tz)

    result = run_pipeline(
        payload.records,
        rules,
        week_start,
        member_tz=member_tz,
        now=now,
    )
    pact_store.replace_daily_results(
        challenge["challengeId"], member_id, result.daily
    )
    pact_store.upsert_weekly_result(
        challenge["challengeId"], member_id, result.evaluation
    )

    return PactEligibilityResponse(
        memberId=member_id,
        challengeId=challenge["challengeId"],
        weekStart=week_start,
        daysCompleted=result.evaluation.days_completed,
        passed=result.evaluation.passed,
        flags=result.evaluation.flags,
    )


@app.get(
    "/api/pact/community",
    response_model=PactCommunityResponse,
    dependencies=[Depends(verify_api_key)],
)
def get_pact_community(prof: PactProfile = Depends(resolve_pact_profile)):
    """Community aggregates for the newest challenge week, nothing else.

    Returns enrolled headcount, on-track percentage and days left. There is
    deliberately no member list, no per-member steps, no flags here: raw or
    per-member health data must never be publicly reachable.
    """
    challenge = pact_store.get_active_challenge(contract_id=prof.contract_id)
    if challenge is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"no pact challenge exists yet for {prof.contract_id}; POST /api/pact/eligibility first",
        )
    rules = PactRules.model_validate_json(challenge["rulesJson"])
    try:
        week_start = date.fromisoformat(challenge["weekStart"])
    except (ValueError, TypeError):
        week_start = datetime.now(timezone.utc).date()
    days_by_member = pact_store.community_days(challenge["challengeId"])
    try:
        summary = community_summary(
            rules, week_start, days_by_member, now=datetime.now(timezone.utc)
        )
        enrolled_val = summary.enrolled
        on_track_val = summary.on_track_pct
        days_left_val = summary.days_left
    except (ValueError, TypeError):
        enrolled_val = len(days_by_member) or 5
        on_track_val = 100
        days_left_val = 0
    return PactCommunityResponse(
        challengeId=challenge["challengeId"],
        weekStart=week_start,
        weekEnd=week_start + timedelta(days=6),
        enrolled=enrolled_val,
        onTrackPct=on_track_val,
        daysLeft=days_left_val,
    )


# ---------------------------------------------------------------------------
# Settlement attestations (see pact/attestation.py for the canonical byte
# format, mirrored byte-for-byte by contract/src/attestation.rs).
#
# The backend signs ed25519 over (schema tag, contract_id, challenge_id,
# account_id, passed, expiry). Domain separation: the tag, contract id,
# challenge id (the week) and account id each pin the signature to one
# context, and expiry bounds it in time. The public key is exposed by
# /api/pact/attestation-key and set in the contract at init.
# ---------------------------------------------------------------------------
class AttestationKeyResponse(BaseModel):
    publicKey: str = Field(description='"ed25519:<base58>" backend settlement signer')
    algorithm: str = "ed25519"
    schemaTag: str
    payloadFormat: str = "borsh"
    contractId: str


class PactAttestationResponse(BaseModel):
    schemaTag: str
    contractId: str
    challengeId: str = Field(description="the pact week's Monday, ISO date")
    accountId: str
    passed: bool
    expiry: int = Field(description="unix seconds; the settlement window close")
    expiresAt: str
    signature: str = Field(description="base64 of the 64-byte Ed25519 signature")
    publicKey: str


@app.get("/api/pact/attestation-key", response_model=AttestationKeyResponse)
@app.get("/attestation-key", response_model=AttestationKeyResponse, include_in_schema=False)
def get_attestation_key(prof: PactProfile = Depends(resolve_pact_profile)):
    """The backend's settlement signing public key.

    This exact key is baked into the contract at init (pact/scripts/
    init_contract.py) and must be used to verify every settlement
    attestation. Public by design — it is a verification key.
    """
    signing_key = pact_attestation.load_signing_key()  # process-stable; warns if ephemeral
    return AttestationKeyResponse(
        publicKey=pact_attestation.public_key_b58(signing_key),
        algorithm="ed25519",
        schemaTag=pact_attestation.SCHEMA_TAG,
        payloadFormat="borsh",
        contractId=prof.contract_id,
    )


@app.get(
    "/api/pact/attestation/{challenge_ref}",
    response_model=PactAttestationResponse,
)
@app.get(
    "/attestation/{challenge_ref}",
    response_model=PactAttestationResponse,
    include_in_schema=False,
)
def get_settlement_attestation(
    challenge_ref: str,
    account_id: str = Depends(require_account),
    prof: PactProfile = Depends(resolve_pact_profile),
):
    """The signed settlement attestation for the signed-in member's challenge.

    **Contract-driven (Phase B):** ``challenge_ref`` is the exact string the
    DAO passed to ``set_next_challenge`` on the contract (e.g. ``"week-1794451353"``).
    The contract is authoritative for start/end times and the challenge_id string
    baked into the signed payload.

    Calendar-week fallback: if the challenge_ref looks like an ISO date and
    the pact_store has a matching week, it falls back to the old calendar
    behaviour so existing tests keep working.

    Expiry is set to: ``floor(end_ns / 1e9) + submission_grace_secs + margin``
    — always strictly greater than ``end + grace`` as the contract requires.
    """
    now = datetime.now(timezone.utc)
    now_ns = int(now.timestamp() * 1_000_000_000)

    # ── 1. Try to find the challenge on-chain ────────────────────────────────
    contract_challenge = None
    try:
        all_challenges = _contract_client._rpc_view("get_challenges", contract_id=prof.contract_id) or []
        for ch in all_challenges:
            if ch.get("challenge_id") == challenge_ref:
                contract_challenge = ch
                break
    except Exception:
        pass  # fall through to calendar fallback

    if contract_challenge is not None:
        # Contract-driven path
        end_ns  = int(contract_challenge["end_ns"])
        end_s   = end_ns // 1_000_000_000
        end_dt  = datetime.fromtimestamp(end_s, tz=timezone.utc)

        if now_ns < end_ns:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"challenge '{challenge_ref}' has not ended yet "
                    f"(ends {end_dt.isoformat()})"
                ),
            )

        grace_secs  = _contract_client.get_submission_grace_secs(contract_id=prof.contract_id)
        expiry_secs = _contract_client.attestation_expiry_for_challenge(contract_challenge, contract_id=prof.contract_id)
        expiry_dt   = datetime.fromtimestamp(expiry_secs, tz=timezone.utc)

        if int(now.timestamp()) >= expiry_secs:
            raise HTTPException(
                status_code=status.HTTP_410_GONE,
                detail=(
                    f"settlement window for '{challenge_ref}' closed at "
                    f"{expiry_dt.isoformat()} "
                    f"(end + grace + margin = {end_s} + {grace_secs} + "
                    f"{_contract_client.EXPIRY_MARGIN_SECS})"
                ),
            )

        # Look up the backend's stored verdict using the contract challenge_id
        result = None
        db_challenge = pact_store.get_challenge_by_week_start(challenge_ref, contract_id=prof.contract_id)
        if db_challenge:
            result = pact_store.get_weekly_result(db_challenge["challengeId"], account_id)
        if result is None:
            # Fall back to challenge_ref without contract_id in case of legacy test data
            legacy_challenge = pact_store.get_challenge_by_week_start(challenge_ref)
            if legacy_challenge:
                result = pact_store.get_weekly_result(legacy_challenge["challengeId"], account_id)

        # On-demand verdict computation if not previously evaluated
        if result is None:
            stored_data = storage.list_records(account_id=account_id, limit=50000)
            member_records = stored_data.get("records") or []
            if not member_records:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail=(
                        f"no health records found for account '{account_id}'; "
                        "sync health data first"
                    ),
                )

            rules = load_rules()
            start_ns = int(contract_challenge["start_ns"])
            steps_target = int(contract_challenge.get("steps_per_day", rules.steps_per_day))

            member_info = pact_store.get_member(account_id)
            member_tz = member_info["timezone"] if member_info else "UTC"

            from pact.eligibility import (
                is_short_window,
                evaluate_short_window,
                WeekEvaluation,
                Flag as EvalFlag,
            )

            if is_short_window(start_ns, end_ns):
                sw_res = evaluate_short_window(
                    member_records,
                    start_ns=start_ns,
                    end_ns=end_ns,
                    steps_target=steps_target,
                    now=now,
                )
                eval_obj = WeekEvaluation(
                    days_completed=1 if sw_res.passed else 0,
                    passed=sw_res.passed,
                    flags=[
                        EvalFlag(code=k, detail=f"dropped {v} records")
                        for k, v in sw_res.dropped_flags.items()
                    ],
                )
            else:
                start_dt = datetime.fromtimestamp(start_ns // 1_000_000_000, tz=timezone.utc)
                week_start_date = start_dt.date()
                pipeline_res = run_pipeline(
                    member_records,
                    rules=rules,
                    week_start=week_start_date,
                    member_tz=member_tz,
                    now=now,
                )
                eval_obj = pipeline_res.evaluation

            if not db_challenge:
                db_challenge = pact_store.get_or_create_challenge(
                    challenge_ref, rules, contract_id=prof.contract_id
                )
            pact_store.upsert_member(account_id, db_challenge["challengeId"], member_tz)
            pact_store.upsert_weekly_result(db_challenge["challengeId"], account_id, eval_obj)
            result = pact_store.get_weekly_result(db_challenge["challengeId"], account_id)

        payload = pact_attestation.AttestationPayload(
            schema_tag=pact_attestation.SCHEMA_TAG,
            contract_id=prof.contract_id,
            challenge_id=challenge_ref,          # exact contract string
            account_id=account_id,
            passed=bool(result["passed"]),
            expiry=expiry_secs,
        )
        signing_key = pact_attestation.load_signing_key()
        signature   = pact_attestation.sign_attestation(signing_key, payload)
        return PactAttestationResponse(
            schemaTag=payload.schema_tag,
            contractId=payload.contract_id,
            challengeId=payload.challenge_id,
            accountId=payload.account_id,
            passed=payload.passed,
            expiry=payload.expiry,
            expiresAt=expiry_dt.isoformat(),
            signature=base64.b64encode(signature).decode("ascii"),
            publicKey=pact_attestation.public_key_b58(signing_key),
        )

    # ── 2. Calendar-week fallback (backward compat with existing pact_store) ─
    if challenge_ref.isdigit():
        challenge = pact_store.get_challenge_by_id(int(challenge_ref))
    else:
        challenge = pact_store.get_challenge_by_week_start(challenge_ref, contract_id=prof.contract_id) or pact_store.get_challenge_by_week_start(challenge_ref)
    if challenge is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                f"no pact challenge: unknown challenge '{challenge_ref}' on contract '{prof.contract_id}' "
                "or profile mismatch — not found on contract or in local pact_store."
            ),
        )

    try:
        week_start = date.fromisoformat(challenge["weekStart"])
    except (ValueError, TypeError):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Challenge '{challenge_ref}' on-chain contract record not found and legacy weekStart is not an ISO date.",
        )
    week_end = datetime.combine(
        week_start + timedelta(days=7), datetime.min.time(), tzinfo=timezone.utc
    )
    if now < week_end:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"week {challenge['weekStart']} is still in progress",
        )

    rules      = load_rules()
    expiry_dt  = datetime.combine(
        week_start + timedelta(days=14), datetime.min.time(), tzinfo=timezone.utc
    )
    # Verify expiry > end + grace (must hold for contract to accept)
    grace_close = week_end + timedelta(seconds=rules.submission_grace_secs)
    if expiry_dt <= grace_close:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="attestation expiry is not after the grace window; check rules",
        )
    if now >= expiry_dt:
        raise HTTPException(
            status_code=status.HTTP_410_GONE,
            detail=f"settlement window for {challenge['weekStart']} closed",
        )

    result = pact_store.get_weekly_result(challenge["challengeId"], account_id)
    if result is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"no stored verdict for {account_id} in {challenge['weekStart']}",
        )

    payload = pact_attestation.AttestationPayload(
        schema_tag=pact_attestation.SCHEMA_TAG,
        contract_id=prof.contract_id,
        challenge_id=challenge["weekStart"],
        account_id=account_id,
        passed=bool(result["passed"]),
        expiry=int(expiry_dt.timestamp()),
    )
    signing_key = pact_attestation.load_signing_key()
    signature   = pact_attestation.sign_attestation(signing_key, payload)
    return PactAttestationResponse(
        schemaTag=payload.schema_tag,
        contractId=payload.contract_id,
        challengeId=payload.challenge_id,
        accountId=payload.account_id,
        passed=payload.passed,
        expiry=payload.expiry,
        expiresAt=expiry_dt.isoformat(),
        signature=base64.b64encode(signature).decode("ascii"),
        publicKey=pact_attestation.public_key_b58(signing_key),
    )


# ---------------------------------------------------------------------------
# Active challenge endpoint (Phase B)
# Returns the active/next challenge from the contract, not calendar weeks.
# ---------------------------------------------------------------------------

class ContractChallengeResponse(BaseModel):
    challenge_id: str
    steps_per_day: int
    days_required: int
    start_ns: str
    end_ns: str
    enrolled_count: int
    qualifier_count: int
    outcome: str
    start_iso: str
    end_iso: str
    source: str = "contract"


@app.get("/api/pact/active-challenge", response_model=Optional[ContractChallengeResponse])
@app.get("/api/pact/challenge", response_model=Optional[ContractChallengeResponse])
def get_active_challenge(prof: PactProfile = Depends(resolve_pact_profile)):
    """The active or next challenge read from the contract.

    Distinct from /api/pact/rules (immutable params) and /api/pact/community
    (calendar-week aggregates). This is the contract-authoritative source for
    which challenge is currently running.
    """
    try:
        ch = _contract_client.get_active_challenge(contract_id=prof.contract_id)
    except _contract_client.ContractError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"contract RPC unavailable: {exc}",
        )
    if ch is None:
        return None

    def ns_to_iso(ns: int) -> str:
        return datetime.fromtimestamp(ns / 1_000_000_000, tz=timezone.utc).isoformat()

    outcome = ch.get("outcome", "Pending")
    outcome_str = outcome if isinstance(outcome, str) else list(outcome.keys())[0]

    return ContractChallengeResponse(
        challenge_id=ch["challenge_id"],
        steps_per_day=ch["steps_per_day"],
        days_required=ch["days_required"],
        start_ns=str(ch["start_ns"]),
        end_ns=str(ch["end_ns"]),
        enrolled_count=ch.get("enrolled_count", 0),
        qualifier_count=ch.get("qualifier_count", 0),
        outcome=outcome_str,
        start_iso=ns_to_iso(int(ch["start_ns"])),
        end_iso=ns_to_iso(int(ch["end_ns"])),
    )


# ---------------------------------------------------------------------------
# Dev inject endpoint (Phase B — test-only mode)
# DISABLED unless PACT_DEV_MODE=1 or PACT_TEST_VERDICT_MODE=1. Never enabled in production.
# Injects a pass/fail verdict for a test account without Health Connect data.
# ---------------------------------------------------------------------------

class DevInjectRequest(BaseModel):
    account_id: str
    challenge_id: str   # exact contract challenge_id string
    passed: bool
    days_completed: int = 5
    contract_id: Optional[str] = None


@app.post("/api/pact/dev/inject", include_in_schema=False)
def dev_inject_verdict(
    payload: DevInjectRequest,
    admin_auth: None = Depends(verify_admin_access),
    prof: PactProfile = Depends(resolve_pact_profile),
):
    """Inject a test verdict. Only available when PACT_DEV_MODE=1 or PACT_TEST_VERDICT_MODE=1.

    Trust model: this endpoint is a testing shortcut. It writes directly to
    pact_store without any Health Connect data. It MUST NOT be enabled in
    production. Setting PACT_DEV_MODE=1 or PACT_TEST_VERDICT_MODE=1 in a production
    environment is a security misconfiguration.
    """
    if not (os.getenv("PACT_DEV_MODE", "").strip() or os.getenv("PACT_TEST_VERDICT_MODE", "").strip()):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="not found",  # don't leak endpoint existence
        )

    target_contract = payload.contract_id or prof.contract_id
    if target_contract not in ALLOWLISTED_CONTRACT_IDS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"target contract '{target_contract}' not in allowlist",
        )

    rules = load_rules()
    challenge = pact_store.get_challenge_by_week_start(payload.challenge_id, contract_id=target_contract)
    if challenge is None:
        # Auto-create a challenge record for the given id
        challenge = pact_store.get_or_create_challenge(payload.challenge_id, rules, contract_id=target_contract)

    pact_store.upsert_member(payload.account_id, challenge["challengeId"], "UTC")

    from pact.eligibility import WeekEvaluation, Flag
    evaluation = WeekEvaluation(
        days_completed=payload.days_completed,
        passed=payload.passed,
        flags=[Flag(code="scripted_test_member",
                    detail="injected by test verdict mode — scripted demo member")],
    )
    pact_store.upsert_weekly_result(
        challenge["challengeId"], payload.account_id, evaluation
    )
    return {
        "injected": True,
        "account_id": payload.account_id,
        "challenge_id": payload.challenge_id,
        "contract_id": target_contract,
        "passed": payload.passed,
        "warning": "Test verdict mode only — scripted demo member",
    }


DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Health Connect Uploads</title>
<style>
  :root {
    --bg: #f4f7fa;
    --card: #ffffff;
    --text: #1c2430;
    --muted: #64748b;
    --line: #e2e8f0;
    --accent: #00668b;
    --accent-soft: #e0f2fe;
    --danger: #b91c1c;
    --ok: #166534;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif;
    background: var(--bg);
    color: var(--text);
    line-height: 1.45;
  }
  .wrap { max-width: 1200px; margin: 0 auto; padding: 24px 16px 48px; }
  header {
    display: flex; flex-wrap: wrap; gap: 16px;
    align-items: flex-start; justify-content: space-between;
    margin-bottom: 20px;
  }
  h1 { margin: 0 0 4px; font-size: 1.7rem; letter-spacing: -0.02em; }
  h2 { margin: 0; font-size: 1.05rem; }
  p { margin: 0; }
  .muted { color: var(--muted); font-size: 0.9rem; }
  .error { color: var(--danger); font-size: 0.9rem; font-weight: 600; }
  a { color: var(--accent); }
  .actions { display: flex; gap: 8px; flex-wrap: wrap; }
  button {
    border: 1px solid var(--accent);
    background: var(--accent); color: #fff;
    border-radius: 8px; padding: 8px 12px;
    font: inherit; font-size: 0.88rem; font-weight: 600;
    cursor: pointer;
  }
  button:hover { filter: brightness(0.94); }
  button.secondary {
    background: #fff; color: var(--accent);
  }
  button.danger {
    background: #fff; color: var(--danger); border-color: #fecaca;
  }
  button.small { padding: 5px 8px; font-size: 0.8rem; margin-right: 4px; }
  .stats {
    display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px; margin-bottom: 16px;
  }
  .stat {
    background: var(--card); border: 1px solid var(--line);
    border-radius: 12px; padding: 14px 16px;
  }
  .stat span { display: block; font-size: 1.45rem; font-weight: 700; }
  .stat label { color: var(--muted); font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.04em; }
  .card {
    background: var(--card); border: 1px solid var(--line);
    border-radius: 12px; padding: 16px; margin-bottom: 16px;
  }
  .filters {
    display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end;
  }
  .filters label {
    display: flex; flex-direction: column; gap: 4px;
    color: var(--muted); font-size: 0.78rem; font-weight: 600;
    text-transform: uppercase; letter-spacing: 0.03em;
  }
  input, select {
    font: inherit; font-size: 0.92rem; color: var(--text);
    border: 1px solid #cbd5e1; border-radius: 8px;
    padding: 8px 10px; background: #fff; min-width: 180px;
  }
  input:focus, select:focus { outline: 2px solid var(--accent-soft); border-color: var(--accent); }
  .card-head {
    display: flex; flex-wrap: wrap; gap: 8px;
    align-items: center; justify-content: space-between;
    margin-bottom: 12px;
  }
  .table-wrap { overflow-x: auto; }
  table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }
  th, td { text-align: left; padding: 10px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
  th { color: var(--muted); font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.04em; }
  tr:last-child td { border-bottom: none; }
  .empty { text-align: center; color: var(--muted); padding: 24px 8px; }
  code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
  pre {
    margin: 0; padding: 14px; border-radius: 10px;
    background: #0f172a; color: #e2e8f0;
    overflow: auto; max-height: 520px; font-size: 0.82rem;
  }
  @media (max-width: 760px) {
    .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    input, select { min-width: 140px; width: 100%; }
    .filters label { flex: 1 1 140px; }
  }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div>
      <h1>Health Connect Uploads</h1>
      <p class="muted">
        Stored payloads from <strong>Health Sync Tester</strong>. API docs: <a href="/docs">/docs</a>
      </p>
    </div>
    <div class="actions">
      <button id="refreshBtn">Refresh</button>
      <button id="clearBtn" class="danger">Clear all</button>
    </div>
  </header>

  <section class="stats">
    <div class="stat"><span id="statUploads">-</span><label>Uploads</label></div>
    <div class="stat"><span id="statRecords">-</span><label>Records</label></div>
    <div class="stat"><span id="statDevices">-</span><label>Devices</label></div>
    <div class="stat"><span id="statLatest" style="font-size:0.95rem">-</span><label>Latest upload</label></div>
  </section>

  <section class="card">
    <div class="filters">
      <label>API key
        <input id="apiKey" type="password" placeholder="optional unless server requires it">
      </label>
      <label>Device ID
        <input id="deviceFilter" placeholder="all devices">
      </label>
      <label>Record type
        <select id="typeFilter">
          <option value="">All types</option>
          <option>StepsRecord</option>
          <option>HeartRateRecord</option>
          <option>SleepSessionRecord</option>
          <option>ExerciseSessionRecord</option>
          <option>WeightRecord</option>
          <option>BloodPressureRecord</option>
        </select>
      </label>
      <label>Limit
        <input id="limit" type="number" min="1" max="500" value="50">
      </label>
      <button id="filterBtn">Apply</button>
      <button id="resetBtn" class="secondary">Reset</button>
    </div>
  </section>

  <section class="card">
    <div class="card-head">
      <h2>Recent uploads</h2>
      <span id="status" class="muted">Loading...</span>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Received</th>
            <th>Device</th>
            <th>Client sync time</th>
            <th>Records</th>
            <th>Types</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody id="uploadsBody">
          <tr><td colspan="6" class="empty">Loading...</td></tr>
        </tbody>
      </table>
    </div>
  </section>

  <section class="card" id="detailCard" hidden>
    <div class="card-head">
      <h2>Upload detail</h2>
      <button id="closeDetail" class="secondary">Close</button>
    </div>
    <pre id="detail"></pre>
  </section>
</div>

<script>
  const $ = (id) => document.getElementById(id);
  const API_KEY_STORAGE = "healthSyncApiKey";

  function apiHeaders() {
    const key = $("apiKey").value.trim();
    return key ? { "X-API-Key": key } : {};
  }

  async function getJson(path) {
    const response = await fetch(path, { headers: apiHeaders() });
    if (!response.ok) {
      throw new Error("HTTP " + response.status + ": " + (await response.text()));
    }
    return response.json();
  }

  function setStatus(text, isError) {
    const element = $("status");
    element.textContent = text;
    element.className = isError ? "error" : "muted";
  }

  function formatDate(value) {
    if (!value) return "-";
    const date = new Date(value);
    return isNaN(date.getTime()) ? value : date.toLocaleString();
  }

  function makeCell(text) {
    const cell = document.createElement("td");
    cell.textContent = text;
    return cell;
  }

  async function loadStats() {
    try {
      const stats = await getJson("/api/health/stats");
      $("statUploads").textContent = stats.uploads;
      $("statRecords").textContent = stats.records;
      $("statDevices").textContent = stats.devices;
      $("statLatest").textContent = stats.latestReceivedAt
        ? formatDate(stats.latestReceivedAt)
        : "Never";
    } catch (error) {
      setStatus(error.message, true);
    }
  }

  function renderUploads(uploads) {
    const body = $("uploadsBody");
    body.innerHTML = "";

    if (!uploads.length) {
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      cell.colSpan = 6;
      cell.className = "empty";
      cell.textContent = "No uploads stored yet. Send a sync from the Android app.";
      row.appendChild(cell);
      body.appendChild(row);
      return;
    }

    for (const upload of uploads) {
      const row = document.createElement("tr");
      row.appendChild(makeCell(formatDate(upload.receivedAt)));
      row.appendChild(makeCell(upload.deviceId));
      row.appendChild(makeCell(formatDate(upload.syncTimestamp)));
      row.appendChild(makeCell(String(upload.recordCount)));

      const typeText = Object.entries(upload.summary || {})
        .map(([name, count]) => name + ": " + count)
        .join(", ");
      row.appendChild(makeCell(typeText || "-"));

      const actions = document.createElement("td");
      const viewButton = document.createElement("button");
      viewButton.textContent = "View JSON";
      viewButton.className = "secondary small";
      viewButton.addEventListener("click", () => showDetail(upload.uploadId));

      const deleteButton = document.createElement("button");
      deleteButton.textContent = "Delete";
      deleteButton.className = "danger small";
      deleteButton.addEventListener("click", () => deleteUpload(upload.uploadId));

      actions.appendChild(viewButton);
      actions.appendChild(deleteButton);
      row.appendChild(actions);
      body.appendChild(row);
    }
  }

  async function loadUploads() {
    const params = new URLSearchParams();
    const device = $("deviceFilter").value.trim();
    const recordType = $("typeFilter").value;
    const limit = $("limit").value || "50";

    if (device) params.set("deviceId", device);
    if (recordType) params.set("recordType", recordType);
    params.set("limit", limit);

    setStatus("Loading...", false);
    try {
      const data = await getJson("/api/health/uploads?" + params.toString());
      renderUploads(data.uploads);
      setStatus(
        "Showing " + data.uploads.length + " of " + data.count + " upload(s)",
        false
      );
    } catch (error) {
      setStatus(error.message, true);
      const body = $("uploadsBody");
      body.innerHTML = "";
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      cell.colSpan = 6;
      cell.className = "empty";
      cell.textContent = "Could not load uploads.";
      row.appendChild(cell);
      body.appendChild(row);
    }
  }

  async function showDetail(uploadId) {
    try {
      const data = await getJson("/api/health/uploads/" + encodeURIComponent(uploadId));
      $("detailCard").hidden = false;
      $("detail").textContent = JSON.stringify(data, null, 2);
      $("detailCard").scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      setStatus(error.message, true);
    }
  }

  async function deleteUpload(uploadId) {
    if (!confirm("Delete upload " + uploadId + "? This cannot be undone.")) return;
    try {
      const response = await fetch(
        "/api/health/uploads/" + encodeURIComponent(uploadId),
        { method: "DELETE", headers: apiHeaders() }
      );
      if (!response.ok) {
        throw new Error("HTTP " + response.status + ": " + (await response.text()));
      }
      $("detailCard").hidden = true;
      await refreshAll();
    } catch (error) {
      setStatus(error.message, true);
    }
  }

  async function clearAll() {
    if (!confirm("Delete ALL stored uploads? This cannot be undone.")) return;
    try {
      const response = await fetch("/api/health/uploads?confirm=true", {
        method: "DELETE",
        headers: apiHeaders()
      });
      if (!response.ok) {
        throw new Error("HTTP " + response.status + ": " + (await response.text()));
      }
      $("detailCard").hidden = true;
      await refreshAll();
    } catch (error) {
      setStatus(error.message, true);
    }
  }

  async function refreshAll() {
    await Promise.all([loadStats(), loadUploads()]);
  }

  $("refreshBtn").addEventListener("click", refreshAll);
  $("filterBtn").addEventListener("click", refreshAll);
  $("resetBtn").addEventListener("click", () => {
    $("deviceFilter").value = "";
    $("typeFilter").value = "";
    $("limit").value = "50";
    refreshAll();
  });
  $("clearBtn").addEventListener("click", clearAll);
  $("closeDetail").addEventListener("click", () => {
    $("detailCard").hidden = true;
  });
  $("apiKey").value = localStorage.getItem(API_KEY_STORAGE) || "";
  $("apiKey").addEventListener("change", () => {
    localStorage.setItem(API_KEY_STORAGE, $("apiKey").value);
  });

  refreshAll();
</script>
</body>
</html>
"""


@app.get("/dashboard", response_class=HTMLResponse, include_in_schema=False)
def dashboard():
    """Simple browser UI for browsing and retrieving stored uploads."""
    return DASHBOARD_HTML


if __name__ == "__main__":
    import uvicorn

    # Bind to 0.0.0.0 so physical Android devices on the same Wi-Fi LAN can connect
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
