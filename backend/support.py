"""Shared helpers for the backend endpoint tests (sign-in + pact fixtures).

Import ORDER matters: the importing test module must set its env vars and
sys.path entries BEFORE importing this module — it imports main, which
freezes env-derived configuration at import time, exactly like the app.

Deliberately NOT named ``test_*.py``: pytest must not collect this file on
its own (a bare collection would import main without any test env vars
set, pointing the stores at the real dev databases).

Provides:
- one shared TestClient for the app
- a fake NEAR chain (context manager): near_auth.fetch_access_keys is a
  module-level function and main.py calls it at request time, so swapping
  it here makes the running app see a controlled chain (no network)
- full NEP-413 sign-in helpers returning ready Authorization headers
- expiry manipulation for nonce/session tests
- the pact fixture shifting test_pact.py has always used
"""

from __future__ import annotations

import base64
import hashlib
import json
from contextlib import contextmanager
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional

from fastapi.testclient import TestClient
from nacl.signing import SigningKey

import auth_store
import near_auth
from main import app
from pact.encoding import b58encode
from pact.eligibility import current_week_start
from pact.rules import load_rules

client = TestClient(app)
RULES = load_rules()

BACKEND_DIR = Path(__file__).resolve().parent
FIXTURES_DIR = BACKEND_DIR.parent / "pact" / "fixtures"
FIXTURE_WEEK_START = date(2026, 11, 9)  # Monday of the fixtures' week

# Fixed at test-suite import time so every shifted fixture in one run lands
# on the same week; tests that need a different week pass their own Monday.
LAST_COMPLETED_WEEK_START = current_week_start(
    datetime.now().astimezone(), RULES, "UTC"
) - timedelta(days=7)


# ---------------------------------------------------------------------------
# Fake NEAR chain
# ---------------------------------------------------------------------------

def full_access_key(public_key: str) -> dict:
    """A key-list entry shaped exactly like the real RPC response
    (captured from rpc.testnet.near.org on 2026-11-11, see test_auth.py)."""
    return {
        "public_key": public_key,
        "access_key": {"nonce": 1, "permission": "FullAccess"},
    }


def function_call_key(public_key: str) -> dict:
    """A function-call (restricted) key: valid on chain, but NOT allowed to
    sign NEP-413 challenges."""
    return {
        "public_key": public_key,
        "access_key": {
            "nonce": 1,
            "permission": {
                "FunctionCall": {
                    "receiver_id": "other.testnet",
                    "method_names": ["noop"],
                    "allowance": "1000000000",
                }
            },
        },
    }


@contextmanager
def fake_chain(
    accounts: Optional[Dict[str, List[dict]]] = None,
    default: Optional[List[dict]] = None,
):
    """Substitute near_auth.fetch_access_keys for the duration of the block.

    ``accounts`` maps account ids to their key lists; accounts missing from
    the map (and with no ``default``) raise AccountNotFoundError, like the
    real RPC does for missing accounts.
    """
    original = near_auth.fetch_access_keys

    def responder(account_id: str, rpc_url: Optional[str] = None) -> List[dict]:
        if accounts and account_id in accounts:
            return accounts[account_id]
        if default is not None:
            return default
        raise near_auth.AccountNotFoundError(
            f"account {account_id!r} does not exist on chain (fake chain)"
        )

    near_auth.fetch_access_keys = responder
    try:
        yield
    finally:
        near_auth.fetch_access_keys = original


def request_nonce(account: str) -> dict:
    response = client.post("/api/auth/nonce", json={"accountId": account})
    assert response.status_code == 200, response.text
    return response.json()


def nep413_sign(signing_key: SigningKey, challenge: dict) -> dict:
    """The /api/auth/verify request body for a /api/auth/nonce challenge."""
    nonce_bytes = base64.b64decode(challenge["nonce"])
    digest = near_auth.nep413_hash(
        challenge["message"], nonce_bytes, challenge["recipient"]
    )
    return {
        "accountId": challenge["accountId"],
        "publicKey": "ed25519:" + b58encode(bytes(signing_key.verify_key)),
        "signature": base64.b64encode(signing_key.sign(digest).signature).decode(),
        "nonce": challenge["nonce"],
    }


def sign_in(account: str, signing_key: Optional[SigningKey] = None) -> dict:
    """Full NEP-413 sign-in through the live endpoints (fake chain active).

    Returns the session: token/headers/key material for authenticated calls.
    """
    signing_key = signing_key or SigningKey.generate()
    challenge = request_nonce(account)
    body = nep413_sign(signing_key, challenge)
    response = client.post("/api/auth/verify", json=body)
    assert response.status_code == 200, response.text
    session = response.json()
    return {
        "account": account,
        "token": session["token"],
        "expiresAt": session["expiresAt"],
        "publicKey": body["publicKey"],
        "signingKey": signing_key,
        "headers": {"Authorization": f"Bearer {session['token']}"},
    }


@contextmanager
def signed_in(account: str, signing_key: Optional[SigningKey] = None) -> dict:
    """Fake chain with one Full Access Key + sign-in, in one step."""
    signing_key = signing_key or SigningKey.generate()
    public_key = "ed25519:" + b58encode(bytes(signing_key.verify_key))
    with fake_chain({account: [full_access_key(public_key)]}):
        yield sign_in(account, signing_key)


@contextmanager
def accounts_signed_in(*account_ids: str) -> Dict[str, dict]:
    """Sign in several members at once against one shared fake chain.

    Yields {account_id: session} — the community/scoping tests need several
    concurrently signed-in members.
    """
    signing_keys: Dict[str, SigningKey] = {}
    chain: Dict[str, List[dict]] = {}
    for account_id in account_ids:
        key = SigningKey.generate()
        signing_keys[account_id] = key
        chain[account_id] = [
            full_access_key("ed25519:" + b58encode(bytes(key.verify_key)))
        ]
    with fake_chain(chain):
        yield {a: sign_in(a, signing_keys[a]) for a in account_ids}


# ---------------------------------------------------------------------------
# Expiry manipulation (simulates time passing)
# ---------------------------------------------------------------------------

def force_expire_nonce(nonce_b64: str) -> None:
    """Push a nonce row into the past (user waited too long to sign)."""
    nonce_hex = base64.b64decode(nonce_b64).hex()
    with auth_store._db() as conn:  # test code may touch the store's internals
        conn.execute(
            "UPDATE auth_nonces SET expires_at = 1.0 WHERE nonce_hex = ?",
            (nonce_hex,),
        )


def force_expire_session(token: str) -> None:
    """Push a session row into the past (token used after its TTL)."""
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    with auth_store._db() as conn:
        conn.execute(
            "UPDATE auth_sessions SET expires_at = 1.0 WHERE token_hash = ?",
            (token_hash,),
        )


# ---------------------------------------------------------------------------
# Pact fixtures, shifted onto a completed week
# ---------------------------------------------------------------------------

def _dt(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def shifted_records(name: str, monday: Optional[date] = None) -> list:
    """Fixture records moved onto the given week's Monday (same times of day)."""
    fixture = json.loads((FIXTURES_DIR / f"{name}.json").read_text(encoding="utf-8"))
    delta = (monday or LAST_COMPLETED_WEEK_START) - FIXTURE_WEEK_START
    shifted = []
    for record in fixture["records"]:
        record = dict(record)
        record["startTime"] = (
            (_dt(record["startTime"]) + delta).isoformat().replace("+00:00", "Z")
        )
        if record.get("endTime"):
            record["endTime"] = (
                (_dt(record["endTime"]) + delta).isoformat().replace("+00:00", "Z")
            )
        shifted.append(record)
    return shifted


def ingest_pact(
    session: dict,
    fixture_name: str,
    timezone: Optional[str] = None,
    week_start: Optional[date] = None,
    extra_headers: Optional[dict] = None,
):
    """POST /api/pact/eligibility as the signed-in session's member."""
    monday = week_start or LAST_COMPLETED_WEEK_START
    body: dict = {"records": shifted_records(fixture_name, monday)}
    body["weekStart"] = monday.isoformat()
    if timezone is not None:
        body["timezone"] = timezone
    req_headers = dict(session["headers"])
    if extra_headers:
        req_headers.update(extra_headers)
    return client.post("/api/pact/eligibility", json=body, headers=req_headers)
