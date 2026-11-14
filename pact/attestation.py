"""Settlement attestation: backend-signed proof that one member passed (or
failed) one pact week, verifiable offline and by the NEAR contract.

Canonical byte format (Borsh; byte-for-byte mirrored in
``contract/src/attestation.rs`` and pinned by the shared test vector in
``contract/test-vectors/settlement_attestation.json``)::

    struct SettlementAttestation {
        schema_tag:   String   = "OPAQUE-PACT-ATTESTATION-V1"   # domain separation
        contract_id:  String                                      # which pact contract settles it
        challenge_id: String                                      # pact week's Monday, ISO date (e.g. "2026-11-09")
        account_id:  String                                      # NEAR account id of the member
        passed:      bool                                        # u8: 0 or 1
        expiry:      u64                                         # unix seconds, little-endian
    }

    signed_message = borsh_serialize(SettlementAttestation)     # raw bytes, no pre-hash
    signature      = ed25519(secret_key, signed_message)         # 64 bytes, RFC 8032

Borsh encoding rules used (https://borsh.io):
- ``String``  -> u32 little-endian byte length + UTF-8 bytes
- ``bool``    -> single byte 0x00 / 0x01
- ``u64``     -> 8 bytes little-endian

Domain separation, so one signature can never be replayed somewhere it was
not meant for:
- the fixed ``schema_tag`` prefix means this is never a NEAR transaction, a
  NEP-413 login challenge, or any other protocol's message;
- ``contract_id`` pins the attestation to one deployed contract;
- ``challenge_id`` (the week's Monday) pins it to one week;
- ``account_id`` pins it to one member;
- ``expiry`` (settlement-window close) makes stale attestations useless.

The backend signs with a key from the environment (secret-manager hook:
``PACT_ATTESTATION_SECRET_KEY``, base64 of the 32-byte seed); the public key
is exposed via ``GET /api/pact/attestation-key`` and is baked into the
contract at init (see ``pact/scripts/init_contract.py``).
"""

from __future__ import annotations

import base64
import logging
import os
import threading
from dataclasses import dataclass
from typing import Optional

from nacl.exceptions import BadSignatureError
from nacl.signing import SigningKey, VerifyKey

from pact.encoding import b58encode

logger = logging.getLogger("pact.attestation")

SCHEMA_TAG = "OPAQUE-PACT-ATTESTATION-V1"

# Env var holding base64(32-byte seed) of the attestation signing key.
SECRET_KEY_ENV = "PACT_ATTESTATION_SECRET_KEY"
# Alternative for read-only consumers (e.g. the init script) that only need
# the public key: base58 32 bytes, with or without the "ed25519:" prefix.
PUBLIC_KEY_ENV = "PACT_ATTESTATION_PUBLIC_KEY"

# Process-wide fallback used only when SECRET_KEY_ENV is unset. Regenerating
# this on every call would make GET /attestation-key and GET /attestation/{id}
# disagree, so the key is minted once per process.
_ephemeral_lock = threading.Lock()
_ephemeral_signing_key: Optional[SigningKey] = None


class AttestationKeyError(RuntimeError):
    """The attestation signing key is missing or malformed."""


def borsh_string(value: str) -> bytes:
    """Borsh ``String``: u32 little-endian length + UTF-8 bytes."""
    encoded = value.encode("utf-8")
    if len(encoded) > 0xFFFFFFFF:
        raise ValueError("string too long for Borsh u32 length prefix")
    return len(encoded).to_bytes(4, "little") + encoded


@dataclass(frozen=True)
class AttestationPayload:
    """The six fields that are Borsh-serialized and Ed25519-signed."""

    schema_tag: str
    contract_id: str
    challenge_id: str  # pact week's Monday, ISO date string
    account_id: str
    passed: bool
    expiry: int  # unix seconds

    def to_borsh(self) -> bytes:
        return (
            borsh_string(self.schema_tag)
            + borsh_string(self.contract_id)
            + borsh_string(self.challenge_id)
            + borsh_string(self.account_id)
            + (b"\x01" if self.passed else b"\x00")
            + self.expiry.to_bytes(8, "little")
        )


def sign_attestation(signing_key: SigningKey, payload: AttestationPayload) -> bytes:
    """Ed25519 signature (64 bytes) over the canonical Borsh serialization."""
    return signing_key.sign(payload.to_borsh()).signature


def verify_attestation(
    public_key_bytes: bytes,
    payload: AttestationPayload,
    signature: bytes,
    now: Optional[int] = None,
) -> bool:
    """Verify a signature over ``payload``'s canonical serialization.

    If ``now`` (unix seconds) is given, an attestation whose ``expiry`` has
    passed is rejected as well — this is the freshness check the contract
    performs with its own clock.
    """
    if now is not None and payload.expiry <= now:
        return False
    if len(public_key_bytes) != 32 or len(signature) != 64:
        return False
    try:
        VerifyKey(public_key_bytes).verify(payload.to_borsh(), signature)
    except BadSignatureError:
        return False
    return True


def generate_signing_key(seed: Optional[bytes] = None) -> SigningKey:
    """A deterministic (fixed seed) or random Ed25519 signing key."""
    if seed is None:
        return SigningKey.generate()
    if len(seed) != 32:
        raise ValueError("Ed25519 seed must be exactly 32 bytes")
    return SigningKey(seed)


def public_key_b58(signing_key: SigningKey) -> str:
    """The public key in NEAR's wire format ``ed25519:<base58>``."""
    return "ed25519:" + b58encode(bytes(signing_key.verify_key))


def load_signing_key(strict: bool = False) -> SigningKey:
    """The settlement signing key from the environment.

    ``PACT_ATTESTATION_SECRET_KEY`` must hold base64 of the 32-byte seed
    (generate one with ``pact/scripts/generate_attestation_key.py``).

    With ``strict=False`` AND ``PACT_DEV_MODE`` unset (production): a missing
    variable raises AttestationKeyError — the backend refuses to serve
    attestations without a real key. No silent ephemeral fallback.

    With ``strict=False`` AND ``PACT_DEV_MODE=1`` (development): a missing
    variable falls back to a per-process ephemeral key so local development
    works; attestations then only verify against that process and a loud
    warning is logged. NEVER set PACT_DEV_MODE in production.

    With ``strict=True`` (deployment tooling such as the contract init
    script): a missing or malformed key is always an error, regardless of
    dev mode.
    """
    raw = os.getenv(SECRET_KEY_ENV, "").strip()
    if raw:
        try:
            seed = base64.b64decode(raw, validate=True)
        except Exception as exc:
            raise AttestationKeyError(
                f"{SECRET_KEY_ENV} is not valid base64: {exc}"
            ) from exc
        if len(seed) != 32:
            raise AttestationKeyError(
                f"{SECRET_KEY_ENV} must decode to exactly 32 bytes "
                f"(an Ed25519 seed), got {len(seed)}"
            )
        return SigningKey(seed)
    if strict:
        raise AttestationKeyError(
            f"{SECRET_KEY_ENV} is not set; generate a key with "
            "pact/scripts/generate_attestation_key.py and export it "
            "(this is the value the deployed contract pins at init)"
        )
    # In non-strict mode, only allow ephemeral fallback if PACT_DEV_MODE is set.
    if not os.getenv("PACT_DEV_MODE", "").strip():
        raise AttestationKeyError(
            f"{SECRET_KEY_ENV} is not set and PACT_DEV_MODE is not set. "
            "The backend refuses to serve attestations without a real key. "
            "Set PACT_ATTESTATION_SECRET_KEY (see "
            "pact/scripts/generate_attestation_key.py), or set "
            "PACT_DEV_MODE=1 for local development only."
        )
    global _ephemeral_signing_key
    with _ephemeral_lock:
        if _ephemeral_signing_key is None:
            _ephemeral_signing_key = SigningKey.generate()
            logger.warning(
                "%s is not set and PACT_DEV_MODE=1: using an EPHEMERAL "
                "in-memory attestation key. Attestations from this process "
                "will not verify anywhere else. Set the env var (see "
                "pact/scripts/generate_attestation_key.py) for stable keys. "
                "NEVER use PACT_DEV_MODE in production.",
                SECRET_KEY_ENV,
            )
        return _ephemeral_signing_key


def reset_ephemeral_signing_key() -> None:
    """Drop the cached fallback key (test helper only)."""
    global _ephemeral_signing_key
    with _ephemeral_lock:
        _ephemeral_signing_key = None


def resolve_attestation_public_key(strict: bool = False) -> str:
    """The attestation public key (``ed25519:<base58>``) for init args.

    Prefers the public key derived from the secret env var; falls back to
    ``PACT_ATTESTATION_PUBLIC_KEY`` (base58, with or without the
    ``ed25519:`` prefix) for consumers that hold only the public half.
    """
    secret = os.getenv(SECRET_KEY_ENV, "").strip()
    if secret:
        seed = base64.b64decode(secret)
        if len(seed) != 32:
            raise AttestationKeyError(
                f"{SECRET_KEY_ENV} must decode to exactly 32 bytes"
            )
        return public_key_b58(SigningKey(seed))

    public = os.getenv(PUBLIC_KEY_ENV, "").strip()
    if public:
        value = public.removeprefix("ed25519:")
        # Sanity-check: base58 decode must yield exactly 32 bytes.
        from pact.encoding import b58decode

        try:
            if len(b58decode(value)) != 32:
                raise ValueError("not 32 bytes")
        except ValueError as exc:
            raise AttestationKeyError(
                f"{PUBLIC_KEY_ENV} is not a valid Ed25519 public key: {exc}"
            ) from exc
        return "ed25519:" + value

    if strict:
        raise AttestationKeyError(
            f"neither {SECRET_KEY_ENV} nor {PUBLIC_KEY_ENV} is set; "
            "generate a key with pact/scripts/generate_attestation_key.py"
        )
    # Non-strict convenience (dev only): derive from an ephemeral key.
    return public_key_b58(load_signing_key(strict=False))


__all__ = [
    "SCHEMA_TAG",
    "AttestationPayload",
    "AttestationKeyError",
    "borsh_string",
    "sign_attestation",
    "verify_attestation",
    "generate_signing_key",
    "public_key_b58",
    "load_signing_key",
    "reset_ephemeral_signing_key",
    "resolve_attestation_public_key",
    "SECRET_KEY_ENV",
    "PUBLIC_KEY_ENV",
]
