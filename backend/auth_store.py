"""SQLite persistence for NEAR sign-in state: nonces and sessions.

Two tables (kept in their own database, ``backend/data/auth.db`` by default,
override with ``HEALTH_SYNC_AUTH_DB`` — read per connection like pact_store,
so import order cannot pin the wrong path):

- auth_nonces   one row per issued NEP-413 challenge nonce; single-use
                (``used_at`` set on the first successful verification) and
                expiring (``expires_at`` unix seconds)
- auth_sessions one row per issued bearer token; only the SHA-256 of the
                token is stored, so the database alone cannot authenticate
                anyone

user_id IS the NEAR account id — there is no separate user table because
the account on chain is the identity.
"""

from __future__ import annotations

import hashlib
import os
import secrets
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterator, Optional

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = BASE_DIR / "data" / "auth.db"

# A NEP-413 challenge is short-lived: the user signs within minutes.
NONCE_TTL_SECONDS = 300
# Sessions last a month (weekly pact, periodic sync); revocation is
# deleting the row — see clear_auth_db / manual SQL.
SESSION_TTL_SECONDS = 30 * 24 * 3600


def _db_path() -> Path:
    return Path(os.getenv("HEALTH_SYNC_AUTH_DB", str(DEFAULT_DB_PATH))).expanduser()


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def _db() -> Iterator[sqlite3.Connection]:
    db_path = _db_path()
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path), timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=5000")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_auth_db() -> None:
    """Create the auth tables if they do not exist."""
    with _db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS auth_nonces (
                nonce_hex TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                message TEXT NOT NULL,
                recipient TEXT NOT NULL,
                issued_at TEXT NOT NULL,
                expires_at REAL NOT NULL,
                used_at TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS auth_sessions (
                token_hash TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                public_key TEXT NOT NULL,
                created_at TEXT NOT NULL,
                expires_at REAL NOT NULL
            )
            """
        )


def issue_nonce(
    nonce: bytes, account_id: str, message: str, recipient: str,
    ttl_seconds: int = NONCE_TTL_SECONDS,
) -> Dict[str, object]:
    """Store a freshly minted 32-byte challenge nonce for one account."""
    if len(nonce) != 32:
        raise ValueError("nonce must be exactly 32 bytes")
    init_auth_db()
    now = datetime.now(timezone.utc).timestamp()
    row = {
        "nonce_hex": nonce.hex(),
        "account_id": account_id,
        "message": message,
        "recipient": recipient,
        "issued_at": _now_iso(),
        "expires_at": now + ttl_seconds,
    }
    with _db() as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO auth_nonces (
                nonce_hex, account_id, message, recipient, issued_at, expires_at, used_at
            ) VALUES (?, ?, ?, ?, ?, ?, NULL)
            """,
            (
                row["nonce_hex"], row["account_id"], row["message"],
                row["recipient"], row["issued_at"], row["expires_at"],
            ),
        )
    return row


def peek_nonce(nonce: bytes) -> Optional[Dict[str, object]]:
    """Return the nonce row if it exists and is still unused and unexpired."""
    init_auth_db()
    with _db() as conn:
        row = conn.execute(
            "SELECT * FROM auth_nonces WHERE nonce_hex = ?", (nonce.hex(),)
        ).fetchone()
    if row is None:
        return None
    if row["used_at"] is not None:
        return None
    if row["expires_at"] <= datetime.now(timezone.utc).timestamp():
        return None
    return {
        "nonce_hex": row["nonce_hex"],
        "account_id": row["account_id"],
        "message": row["message"],
        "recipient": row["recipient"],
        "issued_at": row["issued_at"],
        "expires_at": row["expires_at"],
    }


def consume_nonce(nonce: bytes) -> Optional[Dict[str, object]]:
    """Mark the nonce used. Returns the row on success, None if it was
    already used, unknown, or expired (replay protection: the conditional
    UPDATE makes the win atomic even under concurrent verifies)."""
    init_auth_db()
    now = datetime.now(timezone.utc).timestamp()
    with _db() as conn:
        row = conn.execute(
            "SELECT * FROM auth_nonces WHERE nonce_hex = ?", (nonce.hex(),)
        ).fetchone()
        if row is None:
            return None
        cursor = conn.execute(
            """
            UPDATE auth_nonces SET used_at = ?
            WHERE nonce_hex = ? AND used_at IS NULL AND expires_at > ?
            """,
            (_now_iso(), nonce.hex(), now),
        )
        if cursor.rowcount != 1:  # expired, already used, or lost the race
            return None
        return {
            "nonce_hex": row["nonce_hex"],
            "account_id": row["account_id"],
            "message": row["message"],
            "recipient": row["recipient"],
            "issued_at": row["issued_at"],
            "expires_at": row["expires_at"],
        }


def create_session(
    account_id: str, public_key: str, ttl_seconds: int = SESSION_TTL_SECONDS,
) -> Dict[str, object]:
    """Issue an opaque bearer token for the account; store only its hash."""
    init_auth_db()
    token = secrets.token_urlsafe(32)
    now = datetime.now(timezone.utc)
    with _db() as conn:
        conn.execute(
            """
            INSERT INTO auth_sessions (
                token_hash, account_id, public_key, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?)
            """,
            (
                hashlib.sha256(token.encode("utf-8")).hexdigest(),
                account_id,
                public_key,
                now.isoformat(),
                now.timestamp() + ttl_seconds,
            ),
        )
    expires_at = datetime.fromtimestamp(
        now.timestamp() + ttl_seconds, tz=timezone.utc
    )
    return {
        "token": token,
        "accountId": account_id,
        "publicKey": public_key,
        "expiresAt": expires_at.isoformat(),
    }


def get_session(token: str) -> Optional[Dict[str, object]]:
    """Resolve a bearer token to its session, or None if unknown/expired."""
    init_auth_db()
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    with _db() as conn:
        row = conn.execute(
            "SELECT * FROM auth_sessions WHERE token_hash = ?", (token_hash,)
        ).fetchone()
    if row is None or row["expires_at"] <= datetime.now(timezone.utc).timestamp():
        return None
    return {
        "accountId": row["account_id"],
        "publicKey": row["public_key"],
        "expiresAt": datetime.fromtimestamp(
            row["expires_at"], tz=timezone.utc
        ).isoformat(),
    }


def clear_auth_db() -> int:
    """Delete every nonce and session (test helper). Returns session count."""
    init_auth_db()
    with _db() as conn:
        sessions = conn.execute("SELECT COUNT(*) AS c FROM auth_sessions").fetchone()["c"]
        conn.execute("DELETE FROM auth_nonces")
        conn.execute("DELETE FROM auth_sessions")
        return sessions
