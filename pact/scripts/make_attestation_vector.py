#!/usr/bin/env python3
"""Generate the deterministic settlement-attestation test vector.

Writes ``contract/test-vectors/settlement_attestation.json`` from a FIXED
seed and FIXED fields, so regeneration is byte-stable. The Rust unit test
(contract/tests/attestation_vector.rs) re-serializes the payload with its own
Borsh implementation, asserts the exact bytes match ``payloadBorshHex``, and
verifies the signature with ed25519-dalek — proving the backend-signed
vector verifies in the contract's code (the "done" criterion for the
attestation stage). pact/test_attestation.py re-verifies the same file with
pynacl on the Python side.

Usage:
    python pact/scripts/make_attestation_vector.py [--out PATH]
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from pact.attestation import (  # noqa: E402
    SCHEMA_TAG,
    AttestationPayload,
    generate_signing_key,
    public_key_b58,
    sign_attestation,
)

# Fixed inputs — do not change without regenerating both sides' pins.
VECTOR_SEED = hashlib.sha256(b"opaque-pact-attestation-test-vector").digest()
VECTOR_CONTRACT_ID = "opaque-pact.testnet"
VECTOR_CHALLENGE_ID = "2026-11-09"  # Monday of the fixtures' week
VECTOR_ACCOUNT_ID = "alice.testnet"
VECTOR_PASSED = True
VECTOR_EXPIRY = int(
    datetime(2026, 11, 23, 0, 0, 0, tzinfo=timezone.utc).timestamp()  # week + 14d
)


def build_vector() -> dict:
    signing_key = generate_signing_key(VECTOR_SEED)
    payload = AttestationPayload(
        schema_tag=SCHEMA_TAG,
        contract_id=VECTOR_CONTRACT_ID,
        challenge_id=VECTOR_CHALLENGE_ID,
        account_id=VECTOR_ACCOUNT_ID,
        passed=VECTOR_PASSED,
        expiry=VECTOR_EXPIRY,
    )
    signature = sign_attestation(signing_key, payload)
    return {
        "description": (
            "Deterministic settlement attestation signed by the Python "
            "backend (pact/attestation.py); verified byte-for-byte by the "
            "Rust contract tests (contract/src/attestation.rs mirror)."
        ),
        "schemaTag": payload.schema_tag,
        "contractId": payload.contract_id,
        "challengeId": payload.challenge_id,
        "accountId": payload.account_id,
        "passed": payload.passed,
        "expiry": payload.expiry,
        "publicKey": public_key_b58(signing_key),
        "signature": base64.b64encode(signature).decode("ascii"),
        "payloadBorshHex": payload.to_borsh().hex(),
        "generatedBy": "pact/scripts/make_attestation_vector.py",
    }


def main(argv: Optional[list] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=_REPO_ROOT / "contract" / "test-vectors" / "settlement_attestation.json",
        help="Where to write the vector JSON (default: contract/test-vectors/)",
    )
    args = parser.parse_args(argv)

    vector = build_vector()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(vector, indent=2) + "\n", encoding="utf-8")
    print(f"wrote attestation test vector to {args.out}")
    print(f"  payloadBorshHex: {vector['payloadBorshHex']}")
    print(f"  publicKey:       {vector['publicKey']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
