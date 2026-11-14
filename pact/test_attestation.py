"""Tests for the settlement attestation (canonical bytes + signing).

Covers:
- canonical Borsh serialization pinned to exact bytes (guards accidental
  format drift; the Rust mirror in contract/src/attestation.rs must agree)
- sign/verify round-trip, tamper, wrong-account, wrong-week, expired cases
- the shared test vector (contract/test-vectors/settlement_attestation.json)
  regenerating byte-stably and verifying with pynacl
- key resolution from the environment (strict + ephemeral fallback)
- init_contract.py including attestation_public_key in its init args

Run from the repo root:
    python -m pytest pact/test_attestation.py -q
"""

from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from pact.attestation import (  # noqa: E402
    SCHEMA_TAG,
    AttestationKeyError,
    AttestationPayload,
    generate_signing_key,
    load_signing_key,
    public_key_b58,
    reset_ephemeral_signing_key,
    resolve_attestation_public_key,
    sign_attestation,
    verify_attestation,
)
from pact.encoding import b58decode  # noqa: E402

VECTOR_PATH = REPO_ROOT / "contract" / "test-vectors" / "settlement_attestation.json"

FIXED_PAYLOAD = AttestationPayload(
    schema_tag=SCHEMA_TAG,
    contract_id="opaque-pact.testnet",
    challenge_id="2026-11-09",
    account_id="alice.testnet",
    passed=True,
    expiry=1795478400,
)


def signed_pair(payload: AttestationPayload):
    key = generate_signing_key(b"\x11" * 32)
    return key, sign_attestation(key, payload)


# ---------------------------------------------------------------------------
# Canonical bytes
# ---------------------------------------------------------------------------

def test_canonical_bytes_are_pinned():
    """Byte-exact pin of the Borsh format (mirrors the Rust mirror's test).

    Layout: len-prefixed schema_tag, contract_id, challenge_id,
    account_id; 0x01 for passed; u64 little-endian expiry.
    """
    expected = (
        len("OPAQUE-PACT-ATTESTATION-V1").to_bytes(4, "little")
        + b"OPAQUE-PACT-ATTESTATION-V1"
        + len("opaque-pact.testnet").to_bytes(4, "little")
        + b"opaque-pact.testnet"
        + len("2026-11-09").to_bytes(4, "little")
        + b"2026-11-09"
        + len("alice.testnet").to_bytes(4, "little")
        + b"alice.testnet"
        + b"\x01"
        + (1795478400).to_bytes(8, "little")
    )
    assert FIXED_PAYLOAD.to_borsh() == expected


def test_passed_false_encodes_as_zero():
    payload = AttestationPayload(
        schema_tag=SCHEMA_TAG, contract_id="c.testnet", challenge_id="2026-11-09",
        account_id="bob.testnet", passed=False, expiry=1,
    )
    assert payload.to_borsh()[-9] == 0x00  # bool byte sits before u64 expiry


def test_u64_expiry_is_little_endian():
    payload = AttestationPayload(
        schema_tag=SCHEMA_TAG, contract_id="c", challenge_id="d",
        account_id="a", passed=False, expiry=0x0102030405060708,
    )
    assert payload.to_borsh()[-8:] == b"\x08\x07\x06\x05\x04\x03\x02\x01"


# ---------------------------------------------------------------------------
# Sign / verify round-trips and negative cases
# ---------------------------------------------------------------------------

def test_verify_round_trip():
    key, signature = signed_pair(FIXED_PAYLOAD)
    assert verify_attestation(bytes(key.verify_key), FIXED_PAYLOAD, signature)


def test_tampered_signature_rejected():
    key, signature = signed_pair(FIXED_PAYLOAD)
    tampered = bytes([signature[0] ^ 0xFF]) + signature[1:]
    assert not verify_attestation(bytes(key.verify_key), FIXED_PAYLOAD, tampered)


def test_tampered_payload_rejected():
    """A signature over a flipped `passed` bit must not verify."""
    key, signature = signed_pair(FIXED_PAYLOAD)
    flipped = AttestationPayload(
        schema_tag=SCHEMA_TAG, contract_id=FIXED_PAYLOAD.contract_id,
        challenge_id=FIXED_PAYLOAD.challenge_id, account_id=FIXED_PAYLOAD.account_id,
        passed=not FIXED_PAYLOAD.passed, expiry=FIXED_PAYLOAD.expiry,
    )
    assert not verify_attestation(bytes(key.verify_key), flipped, signature)


def test_wrong_account_rejected():
    key, signature = signed_pair(FIXED_PAYLOAD)
    other = AttestationPayload(
        schema_tag=SCHEMA_TAG, contract_id=FIXED_PAYLOAD.contract_id,
        challenge_id=FIXED_PAYLOAD.challenge_id, account_id="mallory.testnet",
        passed=FIXED_PAYLOAD.passed, expiry=FIXED_PAYLOAD.expiry,
    )
    assert not verify_attestation(bytes(key.verify_key), other, signature)


def test_wrong_week_rejected():
    key, signature = signed_pair(FIXED_PAYLOAD)
    other = AttestationPayload(
        schema_tag=SCHEMA_TAG, contract_id=FIXED_PAYLOAD.contract_id,
        challenge_id="2026-11-16",  # next Monday: a different week
        account_id=FIXED_PAYLOAD.account_id,
        passed=FIXED_PAYLOAD.passed, expiry=FIXED_PAYLOAD.expiry,
    )
    assert not verify_attestation(bytes(key.verify_key), other, signature)


def test_wrong_contract_rejected():
    key, signature = signed_pair(FIXED_PAYLOAD)
    other = AttestationPayload(
        schema_tag=SCHEMA_TAG, contract_id="other-pact.testnet",
        challenge_id=FIXED_PAYLOAD.challenge_id,
        account_id=FIXED_PAYLOAD.account_id,
        passed=FIXED_PAYLOAD.passed, expiry=FIXED_PAYLOAD.expiry,
    )
    assert not verify_attestation(bytes(key.verify_key), other, signature)


def test_schema_tag_domain_separation_matters():
    key, signature = signed_pair(FIXED_PAYLOAD)
    other = AttestationPayload(
        schema_tag="SOME-OTHER-PROTOCOL", contract_id=FIXED_PAYLOAD.contract_id,
        challenge_id=FIXED_PAYLOAD.challenge_id, account_id=FIXED_PAYLOAD.account_id,
        passed=FIXED_PAYLOAD.passed, expiry=FIXED_PAYLOAD.expiry,
    )
    assert not verify_attestation(bytes(key.verify_key), other, signature)


def test_expired_attestation_rejected_only_via_now():
    key, signature = signed_pair(FIXED_PAYLOAD)
    public_key = bytes(key.verify_key)
    # Without a clock check it still verifies (expiry is a payload field)...
    assert verify_attestation(public_key, FIXED_PAYLOAD, signature)
    # ...and with now past the expiry it is rejected.
    assert not verify_attestation(public_key, FIXED_PAYLOAD, signature, now=FIXED_PAYLOAD.expiry)
    assert not verify_attestation(public_key, FIXED_PAYLOAD, signature, now=FIXED_PAYLOAD.expiry + 1)
    # Before the expiry it is fine.
    assert verify_attestation(public_key, FIXED_PAYLOAD, signature, now=FIXED_PAYLOAD.expiry - 1)


def test_malformed_key_or_signature_lengths_rejected():
    key, signature = signed_pair(FIXED_PAYLOAD)
    assert not verify_attestation(b"\x00" * 31, FIXED_PAYLOAD, signature)
    assert not verify_attestation(bytes(key.verify_key), FIXED_PAYLOAD, signature[:63])


# ---------------------------------------------------------------------------
# The shared test vector (also verified by the Rust contract tests)
# ---------------------------------------------------------------------------

def test_vector_file_exists_and_is_deterministic():
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "make_vector", REPO_ROOT / "pact" / "scripts" / "make_attestation_vector.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    on_disk = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
    regenerated = module.build_vector()
    assert on_disk == regenerated, "test vector drifted; regenerate it"


def test_vector_signature_verifies_in_python():
    """The Rust test does the same with ed25519-dalek; this pins the file."""
    vector = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
    payload = AttestationPayload(
        schema_tag=vector["schemaTag"],
        contract_id=vector["contractId"],
        challenge_id=vector["challengeId"],
        account_id=vector["accountId"],
        passed=vector["passed"],
        expiry=vector["expiry"],
    )
    assert payload.to_borsh().hex() == vector["payloadBorshHex"]
    public_key = b58decode(vector["publicKey"].removeprefix("ed25519:"))
    signature = base64.b64decode(vector["signature"], validate=True)
    assert len(signature) == 64
    assert verify_attestation(public_key, payload, signature)
    # ...and the expiry in the vector is already in the past relative to a
    # far-future clock (freshness check semantics).
    assert not verify_attestation(public_key, payload, signature, now=vector["expiry"] + 1)


# ---------------------------------------------------------------------------
# Key handling
# ---------------------------------------------------------------------------

def test_load_signing_key_strict_requires_env(monkeypatch):
    monkeypatch.delenv("PACT_ATTESTATION_SECRET_KEY", raising=False)
    with pytest.raises(AttestationKeyError):
        load_signing_key(strict=True)


def test_load_signing_key_ephemeral_when_unset(monkeypatch):
    monkeypatch.delenv("PACT_ATTESTATION_SECRET_KEY", raising=False)
    monkeypatch.setenv("PACT_DEV_MODE", "1")
    reset_ephemeral_signing_key()
    key_a = load_signing_key(strict=False)
    key_b = load_signing_key(strict=False)
    # Same process must keep one fallback key: otherwise /attestation-key and
    # /attestation/{id} would publish different keys.
    assert bytes(key_a) == bytes(key_b)
    reset_ephemeral_signing_key()
    key_c = load_signing_key(strict=False)
    assert bytes(key_c) != bytes(key_a)


def test_load_signing_key_refuses_without_dev_mode(monkeypatch):
    """Without PACT_ATTESTATION_SECRET_KEY AND without PACT_DEV_MODE,
    the backend must refuse to serve attestations (no ephemeral fallback)."""
    monkeypatch.delenv("PACT_ATTESTATION_SECRET_KEY", raising=False)
    monkeypatch.delenv("PACT_DEV_MODE", raising=False)
    reset_ephemeral_signing_key()
    with pytest.raises(AttestationKeyError):
        load_signing_key(strict=False)


def test_load_signing_key_from_env(monkeypatch):
    seed = b"\x42" * 32
    monkeypatch.setenv("PACT_ATTESTATION_SECRET_KEY", base64.b64encode(seed).decode())
    assert bytes(load_signing_key()) == seed
    assert bytes(load_signing_key(strict=True)) == seed


def test_load_signing_key_rejects_bad_env(monkeypatch):
    monkeypatch.setenv("PACT_ATTESTATION_SECRET_KEY", "!!!not-base64!!!")
    with pytest.raises(AttestationKeyError):
        load_signing_key(strict=True)
    monkeypatch.setenv("PACT_ATTESTATION_SECRET_KEY", base64.b64encode(b"\x01\x02").decode())
    with pytest.raises(AttestationKeyError):
        load_signing_key(strict=True)


def test_public_key_b58_round_trip():
    key = generate_signing_key(b"\x07" * 32)
    wire = public_key_b58(key)
    assert wire.startswith("ed25519:")
    assert b58decode(wire.removeprefix("ed25519:")) == bytes(key.verify_key)


# ---------------------------------------------------------------------------
# Consumer: init_contract.py must pin the attestation key at init
# ---------------------------------------------------------------------------

def test_init_args_include_attestation_public_key(monkeypatch):
    seed = b"\x99" * 32
    monkeypatch.setenv("PACT_ATTESTATION_SECRET_KEY", base64.b64encode(seed).decode())
    sys.path.insert(0, str(REPO_ROOT / "pact" / "scripts"))
    try:
        import init_contract

        args = init_contract.build_init_args()
        assert args["attestation_public_key"] == public_key_b58(generate_signing_key(seed))
        # The rules fields are all still present, unchanged.
        for field in (
            "steps_per_day", "days_required", "stake_yocto", "quorum_pct",
            "mint_amount", "burn_amount", "min_members", "reserve_amount",
            "week_start_day", "day_boundary_timezone_policy",
        ):
            assert field in args
    finally:
        sys.path.remove(str(REPO_ROOT / "pact" / "scripts"))


def test_init_args_strict_without_key(monkeypatch):
    monkeypatch.delenv("PACT_ATTESTATION_SECRET_KEY", raising=False)
    monkeypatch.delenv("PACT_ATTESTATION_PUBLIC_KEY", raising=False)
    sys.path.insert(0, str(REPO_ROOT / "pact" / "scripts"))
    try:
        import init_contract

        with pytest.raises(AttestationKeyError):
            init_contract.build_init_args()
    finally:
        sys.path.remove(str(REPO_ROOT / "pact" / "scripts"))


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
