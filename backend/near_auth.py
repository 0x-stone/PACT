"""NEAR account authentication: NEP-413 challenge verification + chain checks.

Flow (wired by the /api/auth endpoints in main.py):

1. ``POST /api/auth/nonce {accountId}``
   -> the backend mints a single-use 32-byte nonce and returns it with the
      exact ``message`` and ``recipient`` the client must sign.
2. The client signs the NEP-413 challenge with the account's **Full Access
   Key** and posts the result to ``/api/auth/verify``.
3. The backend verifies the Ed25519 signature over the NEP-413 payload
   hash, then confirms via RPC that the claimed public key really is a Full
   Access Key of that account (NEP-413 requirement), then consumes the
   single-use nonce and issues a session token.

NEP-413 payload — exact spec (github.com/near/NEPs, nep-0413.md)::

    struct Payload {
        message:     string;    // Borsh: u32 LE length + UTF-8 bytes
        nonce:       [u8; 32];  // raw 32 bytes
        recipient:   string;
        callbackUrl: string?;   // Borsh Option: 0x00 for None
    }

    signed_hash = sha256( u32_le(2**31 + 413) || borsh(Payload) )
    signature   = ed25519(full_access_key, signed_hash)

Note the signature is over the SHA-256 *hash* (NEAR's convention for
account-key signatures, see nearcore#2835), not over the raw bytes.

Only ``ed25519:`` keys are supported; secp256k1 keys cannot produce this
signature format here.

Honest trust notes:
- ``fetch_access_keys`` is the only network call. It is a module-level
  function (not a closure) so tests can substitute a fake chain — the rest
  of the module is pure.
- The RPC node (default: public testnet) is trusted to report the account's
  access keys. That is the same trust every NEAR client has.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import struct
import urllib.error
import urllib.request
from typing import List, Optional

from pact.encoding import b58decode

# What the user signs, displayed by wallets that show the message.
SIGN_IN_MESSAGE = "Sign in to the Opaque health pact"

# 2**31 + 413: makes the payload an invalid Borsh transaction (NEP-413).
NEP413_TAG = 2**31 + 413

DEFAULT_RPC_URL = "https://rpc.testnet.near.org"
RPC_TIMEOUT_SECONDS = 10

# Cheap pre-RPC sanity gate; the chain remains the authority.
_ACCOUNT_ID_RE = re.compile(r"^[a-z0-9._-]{2,64}$")
_SEPARATORS = "._-"


class NearRpcError(RuntimeError):
    """The NEAR RPC node could not answer the query."""


class AccountNotFoundError(NearRpcError):
    """The account does not exist on chain (yet)."""


def validate_account_id(account_id: str) -> None:
    """Reject obviously malformed account ids before any RPC call.

    NEAR account ids are 2-64 chars of lowercase alphanumerics joined by
    ``.``, ``_`` or ``-``; separators never lead, trail, or repeat. This is
    a convenience gate only — the RPC query in ``fetch_access_keys`` is the
    real authority on existence and key ownership.
    """
    if not isinstance(account_id, str):
        raise ValueError("accountId must be a string")
    if not _ACCOUNT_ID_RE.match(account_id):
        raise ValueError(
            "accountId must be 2-64 chars of lowercase letters, digits, "
            "'.', '_' or '-' (e.g. 'alice.testnet')"
        )
    if account_id[0] in _SEPARATORS or account_id[-1] in _SEPARATORS:
        raise ValueError("accountId must not start or end with '.', '_' or '-'")
    for pair in zip(account_id, account_id[1:]):
        if pair[0] in _SEPARATORS and pair[1] in _SEPARATORS:
            raise ValueError(
                "accountId must not contain two consecutive separators"
            )


def borsh_string(value: str) -> bytes:
    """Borsh ``String``: u32 little-endian length + UTF-8 bytes."""
    encoded = value.encode("utf-8")
    return struct.pack("<I", len(encoded)) + encoded


def nep413_payload(
    message: str,
    nonce: bytes,
    recipient: str,
    callback_url: Optional[str] = None,
) -> bytes:
    """Borsh-serialized NEP-413 ``Payload`` struct, prefixed with the tag."""
    if len(nonce) != 32:
        raise ValueError("NEP-413 nonce must be exactly 32 bytes")
    callback = b"\x00" if callback_url is None else b"\x01" + borsh_string(callback_url)
    body = (
        borsh_string(message)
        + nonce
        + borsh_string(recipient)
        + callback
    )
    return struct.pack("<I", NEP413_TAG) + body


def nep413_hash(
    message: str,
    nonce: bytes,
    recipient: str,
    callback_url: Optional[str] = None,
) -> bytes:
    """The 32-byte SHA-256 digest that gets Ed25519-signed (NEP-413)."""
    return hashlib.sha256(
        nep413_payload(message, nonce, recipient, callback_url)
    ).digest()


def verify_nep413_signature(
    public_key_bytes: bytes,
    signature: bytes,
    message: str,
    nonce: bytes,
    recipient: str,
    callback_url: Optional[str] = None,
) -> bool:
    """True iff ``signature`` is the Ed25519 signature by
    ``public_key_bytes`` over the NEP-413 challenge built from the inputs."""
    if len(public_key_bytes) != 32 or len(signature) != 64:
        return False
    from nacl.exceptions import BadSignatureError
    from nacl.signing import VerifyKey

    digest = nep413_hash(message, nonce, recipient, callback_url)
    try:
        VerifyKey(public_key_bytes).verify(digest, signature)
    except BadSignatureError:
        return False
    return True


def parse_public_key(public_key: str) -> bytes:
    """``"ed25519:<base58>"`` -> 32 raw bytes; ValueError otherwise."""
    if not isinstance(public_key, str) or not public_key.startswith("ed25519:"):
        raise ValueError(
            'publicKey must look like "ed25519:<base58>" '
            "(secp256k1 keys cannot sign NEP-413 challenges here)"
        )
    encoded = public_key[len("ed25519:"):]
    if not encoded:
        raise ValueError("publicKey is missing its base58 part")
    raw = b58decode(encoded)
    if len(raw) != 32:
        raise ValueError(
            f"Ed25519 public keys are 32 bytes, got {len(raw)} after base58 decode"
        )
    return raw


def fetch_access_keys(account_id: str, rpc_url: Optional[str] = None) -> List[dict]:
    """Query the chain for an account's access keys (``view_access_key_list``).

    Returns the RPC ``result.keys`` list — entries shaped like
    ``{"public_key": "ed25519:...", "access_key": {"nonce": int,
    "permission": "FullAccess" | {"FunctionCall": {...}}}}``.

    Raises ``AccountNotFoundError`` when the account does not exist and
    ``NearRpcError`` for other RPC failures. Tests substitute this function
    to fake the chain; everything else in the module is pure.
    """
    url = rpc_url or os.getenv(
        "HEALTH_SYNC_NEAR_RPC_URL", DEFAULT_RPC_URL
    ).strip() or DEFAULT_RPC_URL
    body = json.dumps(
        {
            "jsonrpc": "2.0",
            "id": "opaque-auth",
            "method": "query",
            "params": {
                "request_type": "view_access_key_list",
                "account_id": account_id,
                "finality": "final",
            },
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=RPC_TIMEOUT_SECONDS) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError) as exc:
        raise NearRpcError(f"NEAR RPC call to {url} failed: {exc}") from exc

    if "error" in payload:
        error = payload["error"]
        message = str(error.get("message", ""))
        cause = str(
            (error.get("data") or {}).get("cause", {}).get("name", "")
        )
        if "does not exist" in message or cause == "ACCOUNT_DOES_NOT_EXIST":
            raise AccountNotFoundError(
                f"account {account_id!r} does not exist on chain ({message or cause})"
            )
        raise NearRpcError(f"NEAR RPC error for {account_id!r}: {message or error}")

    keys = (payload.get("result") or {}).get("keys")
    if not isinstance(keys, list):
        raise NearRpcError(
            f"unexpected RPC response for {account_id!r}: no keys array"
        )
    return keys


def is_full_access_key(keys: List[dict], public_key: str) -> bool:
    """True iff ``public_key`` (wire format) is a Full Access Key of the
    account whose key list ``keys`` came from. NEP-413 mandates a Full Access
    Key: function-call keys can be created by any third party and must not
    authenticate anyone."""
    for entry in keys:
        if entry.get("public_key") != public_key:
            continue
        permission = (entry.get("access_key") or {}).get("permission")
        return permission == "FullAccess"
    return False
