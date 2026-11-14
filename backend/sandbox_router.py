"""
Sandbox router for PACT Hackathon Judge self-service.

Enables judges to independently create, monitor, and participate in short
pact challenges on pact-sandbox.testnet without DAO voting or manual intervention.

Endpoints:
  POST /api/sandbox/create-challenge  Create a new sandbox challenge via relayer
  GET  /api/sandbox/status            Current sandbox status & latest challenge lifecycle
"""
from __future__ import annotations

import json
import logging
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, status
from pydantic import BaseModel, Field

_BACKEND_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _BACKEND_DIR.parent
import sys
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from dao.near_rpc import view, near_cli, extract_tx_hash
from backend import auth_store
from pact.profiles import PROFILES

logger = logging.getLogger("sandbox_router")
router = APIRouter(prefix="/api/sandbox", tags=["Judge Sandbox"])

SANDBOX_CONTRACT_ID = PROFILES["sandbox"].contract_id
SANDBOX_RELAYER_ID = PROFILES["sandbox"].dao_id
SUBMISSION_GRACE_SECS = PROFILES["sandbox"].submission_grace_secs

# In-memory deduplication cache and concurrency lock
_RECENT_IDEMPOTENCY_RESPONSES: Dict[str, Dict[str, Any]] = {}
_CREATE_CHALLENGE_LOCK = threading.Lock()


def _background_settle(contract_id: str, challenge_id: str, relayer_id: str):
    """Asynchronously settle completed sandbox challenges outside the critical HTTP path."""
    try:
        near_cli(
            contract_id=contract_id,
            method="settle_week",
            json_args=json.dumps({"challenge_id": challenge_id}),
            sign_as=relayer_id,
            gas="100.0 Tgas",
        )
        logger.info("Background auto-settled previous challenge %s", challenge_id)
    except Exception as e:
        logger.warning("Could not auto-settle %s in background: %s", challenge_id, e)


def _resolve_account(authorization: Optional[str]) -> Optional[str]:
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
            detail="Invalid or expired session; sign in via /api/auth/verify",
        )
    return session["accountId"]


def require_judge_account(authorization: Optional[str] = Header(None)) -> str:
    account_id = _resolve_account(authorization)
    if account_id is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required. Please connect your NEAR wallet.",
        )
    return account_id


def optional_judge_account(authorization: Optional[str] = Header(None)) -> Optional[str]:
    return _resolve_account(authorization)


class CreateSandboxChallengeRequest(BaseModel):
    steps_per_day: int = Field(default=10, ge=1, le=1000, description="Step target for the challenge")
    duration_seconds: int = Field(default=300, ge=60, le=1800, description="Duration in seconds (default 5 mins)")
    countdown_seconds: int = Field(default=120, ge=30, le=300, description="Enrollment countdown in seconds (default 2 mins)")
    idempotency_key: Optional[str] = Field(default=None, description="Optional client idempotency key to prevent double submits")


@router.post("/create-challenge")
def create_sandbox_challenge(
    req: CreateSandboxChallengeRequest,
    background_tasks: BackgroundTasks,
    judge_account_id: str = Depends(require_judge_account),
):
    """
    Judge self-service challenge creation on pact-sandbox.testnet.
    Uses the server-side relayer (pact-sandbox-relayer.testnet) to call set_next_challenge.
    Enforces concurrency checks: strictly one active challenge at a time.
    """
    if req.idempotency_key and req.idempotency_key in _RECENT_IDEMPOTENCY_RESPONSES:
        logger.info("Returning cached response for idempotency_key %s", req.idempotency_key)
        return _RECENT_IDEMPOTENCY_RESPONSES[req.idempotency_key]

    with _CREATE_CHALLENGE_LOCK:
        if req.idempotency_key and req.idempotency_key in _RECENT_IDEMPOTENCY_RESPONSES:
            return _RECENT_IDEMPOTENCY_RESPONSES[req.idempotency_key]

        now = int(time.time())
        now_ns = now * 1_000_000_000

        # 1. Fetch existing challenges and enforce single active challenge invariant
        try:
            challenges = view(SANDBOX_CONTRACT_ID, "get_challenges", {"from_index": 0, "limit": 100}) or []
        except Exception as e:
            logger.error("Failed to query get_challenges from sandbox contract: %s", e)
            raise HTTPException(status_code=500, detail=f"Failed to query sandbox contract: {e}")

        max_existing_end_ns = 0
        for ch in challenges:
            c_start = int(ch["start_ns"])
            c_end = int(ch["end_ns"])
            if c_end > max_existing_end_ns:
                max_existing_end_ns = c_end
            c_outcome = ch.get("outcome", "Pending")
            settlement_cutoff_ns = c_end + (SUBMISSION_GRACE_SECS * 1_000_000_000)

            # 1a. If a challenge is currently live or in-grace, return it idempotently
            if c_outcome == "Pending" and now_ns < settlement_cutoff_ns:
                if now_ns < c_start:
                    phase = "ENROLLING"
                elif now_ns < c_end:
                    phase = "ACTIVE"
                else:
                    phase = "IN_GRACE"
                logger.info("Sandbox challenge %s already in progress (%s); returning existing", ch["challenge_id"], phase)
                start_iso = datetime.fromtimestamp(c_start / 1e9, tz=timezone.utc).isoformat()
                end_iso = datetime.fromtimestamp(c_end / 1e9, tz=timezone.utc).isoformat()
                res = {
                    "success": True,
                    "challenge_id": ch["challenge_id"],
                    "creator": judge_account_id,
                    "steps_per_day": ch.get("steps_per_day", 10),
                    "days_required": ch.get("days_required", 1),
                    "start_ns": str(c_start),
                    "end_ns": str(c_end),
                    "start_iso": start_iso,
                    "end_iso": end_iso,
                    "duration_seconds": req.duration_seconds,
                    "countdown_seconds": req.countdown_seconds,
                    "submission_grace_secs": SUBMISSION_GRACE_SECS,
                    "transaction_id": None,
                    "contract_id": SANDBOX_CONTRACT_ID,
                    "already_active": True,
                    "existing": True,
                    "phase": phase,
                    "enrolled_count": ch.get("enrolled_count", 0),
                }
                if req.idempotency_key:
                    _RECENT_IDEMPOTENCY_RESPONSES[req.idempotency_key] = res
                return res

            # 1b. If previous challenge is past its settlement window, schedule background auto-settle (non-blocking)
            if c_outcome == "Pending" and now_ns >= settlement_cutoff_ns:
                background_tasks.add_task(_background_settle, SANDBOX_CONTRACT_ID, ch["challenge_id"], SANDBOX_RELAYER_ID)

        # 2. Compute dynamic start_ns right before transaction execution
        curr_now = int(time.time())
        earliest_start_sec = curr_now + max(req.countdown_seconds, 30)
        max_end_sec = int(max_existing_end_ns / 1_000_000_000)
        start_sec = max(earliest_start_sec, max_end_sec + 5)
        new_start_ns = start_sec * 1_000_000_000
        new_end_ns = new_start_ns + (req.duration_seconds * 1_000_000_000)

        # 3. Generate unique challenge ID
        challenge_id = f"sb-{curr_now}"
        if len(challenge_id) > 32:
            challenge_id = challenge_id[:32]

        args_json = json.dumps({
            "challenge_id": challenge_id,
            "steps_per_day": req.steps_per_day,
            "days_required": 1,
            "start_ns": str(new_start_ns),
            "end_ns": str(new_end_ns),
        })

        logger.info(
            "Creating sandbox challenge %s by judge %s via relayer %s (starts in %ds)",
            challenge_id, judge_account_id, SANDBOX_RELAYER_ID, (start_sec - curr_now)
        )

        try:
            output = near_cli(
                contract_id=SANDBOX_CONTRACT_ID,
                method="set_next_challenge",
                json_args=args_json,
                sign_as=SANDBOX_RELAYER_ID,
                gas="100.0 Tgas",
            )
        except Exception as e:
            logger.error("Failed to execute set_next_challenge: %s", e)
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"Relayer failed to broadcast set_next_challenge: {e}",
            )

    tx_hash = extract_tx_hash(output)

    # 3. Verify on-chain creation
    created_ch = None
    for _ in range(5):
        try:
            created_ch = view(SANDBOX_CONTRACT_ID, "get_challenge", {"challenge_id": challenge_id})
            if created_ch:
                break
        except Exception:
            pass
        time.sleep(1)

    if not created_ch:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Challenge '{challenge_id}' transaction broadcast ({tx_hash}), but contract verification timed out.",
        )

    start_iso = datetime.fromtimestamp(new_start_ns / 1e9, tz=timezone.utc).isoformat()
    end_iso = datetime.fromtimestamp(new_end_ns / 1e9, tz=timezone.utc).isoformat()

    res = {
        "success": True,
        "challenge_id": challenge_id,
        "creator": judge_account_id,
        "steps_per_day": req.steps_per_day,
        "days_required": 1,
        "start_ns": str(new_start_ns),
        "end_ns": str(new_end_ns),
        "start_iso": start_iso,
        "end_iso": end_iso,
        "duration_seconds": req.duration_seconds,
        "countdown_seconds": req.countdown_seconds,
        "submission_grace_secs": SUBMISSION_GRACE_SECS,
        "transaction_id": tx_hash,
        "contract_id": SANDBOX_CONTRACT_ID,
        "already_active": False,
        "existing": False,
        "phase": "ENROLLING",
        "enrolled_count": 0,
    }
    if req.idempotency_key:
        _RECENT_IDEMPOTENCY_RESPONSES[req.idempotency_key] = res
    return res


@router.get("/status")
def get_sandbox_status(
    judge_account_id: Optional[str] = Depends(optional_judge_account),
):
    """
    Returns full status of the sandbox contract and latest challenge.
    Includes judge membership and claim states if authenticated.
    """
    now = int(time.time())
    now_ns = now * 1_000_000_000

    try:
        challenges = view(SANDBOX_CONTRACT_ID, "get_challenges", {"from_index": 0, "limit": 100}) or []
    except Exception as e:
        logger.error("Failed to query get_challenges: %s", e)
        raise HTTPException(status_code=500, detail=f"Failed to query sandbox contract: {e}")

    # Sort descending by start_ns to get most recent challenge
    sorted_challenges = sorted(challenges, key=lambda c: int(c["start_ns"]), reverse=True)
    latest = sorted_challenges[0] if sorted_challenges else None

    challenge_status = None
    can_create = True

    if latest:
        c_id = latest["challenge_id"]
        c_start = int(latest["start_ns"])
        c_end = int(latest["end_ns"])
        c_outcome = latest.get("outcome", "Pending")
        grace_cutoff_ns = c_end + (SUBMISSION_GRACE_SECS * 1_000_000_000)

        if now_ns < c_start:
            phase = "ENROLLING"
            can_create = False
            seconds_remaining = max(0, int((c_start - now_ns) / 1e9))
        elif now_ns < c_end:
            phase = "ACTIVE"
            can_create = False
            seconds_remaining = max(0, int((c_end - now_ns) / 1e9))
        elif now_ns < grace_cutoff_ns:
            phase = "GRACE_PERIOD"
            can_create = False
            seconds_remaining = max(0, int((grace_cutoff_ns - now_ns) / 1e9))
        else:
            if c_outcome == "Pending":
                phase = "READY_TO_SETTLE"
                can_create = True
                seconds_remaining = 0
            else:
                phase = "SETTLED"
                can_create = True
                seconds_remaining = 0

        # Member status if judge is signed in
        member_status = None
        if judge_account_id:
            try:
                enrolled = view(SANDBOX_CONTRACT_ID, "is_enrolled", {"challenge_id": c_id, "account_id": judge_account_id})
                submitted = view(SANDBOX_CONTRACT_ID, "has_submitted", {"challenge_id": c_id, "account_id": judge_account_id})
                qualifier = view(SANDBOX_CONTRACT_ID, "is_qualifier", {"challenge_id": c_id, "account_id": judge_account_id})
                claimed = view(SANDBOX_CONTRACT_ID, "has_claimed_reward", {"challenge_id": c_id, "account_id": judge_account_id})
                withdrawn = view(SANDBOX_CONTRACT_ID, "has_withdrawn_stake", {"challenge_id": c_id, "account_id": judge_account_id})
                member_status = {
                    "account_id": judge_account_id,
                    "is_enrolled": bool(enrolled),
                    "has_submitted": bool(submitted),
                    "is_qualifier": bool(qualifier),
                    "has_claimed_reward": bool(claimed),
                    "has_withdrawn_stake": bool(withdrawn),
                }
            except Exception as e:
                logger.warning("Failed to query member status for %s: %s", judge_account_id, e)

        challenge_status = {
            "challenge_id": c_id,
            "steps_per_day": latest.get("steps_per_day", 10),
            "days_required": latest.get("days_required", 1),
            "start_ns": str(c_start),
            "end_ns": str(c_end),
            "start_iso": datetime.fromtimestamp(c_start / 1e9, tz=timezone.utc).isoformat(),
            "end_iso": datetime.fromtimestamp(c_end / 1e9, tz=timezone.utc).isoformat(),
            "outcome": c_outcome,
            "enrolled_count": latest.get("enrolled_count", 0),
            "submitted_count": latest.get("submitted_count", 0),
            "qualifier_count": latest.get("qualifier_count", 0),
            "phase": phase,
            "seconds_remaining": seconds_remaining,
            "submission_grace_secs": SUBMISSION_GRACE_SECS,
            "member_status": member_status,
        }

    return {
        "contract_id": SANDBOX_CONTRACT_ID,
        "relayer_id": SANDBOX_RELAYER_ID,
        "stake_yocto": "10000000000000000000000",
        "stake_near": "0.01",
        "quorum_pct": 100,
        "min_members": 1,
        "submission_grace_secs": SUBMISSION_GRACE_SECS,
        "can_create_challenge": can_create,
        "active_challenge": challenge_status,
    }
