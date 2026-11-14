#!/usr/bin/env python3
"""Generate the settlement attestation keypair for the pact backend.

Prints shell exports for the two env vars the rest of the system reads:

- PACT_ATTESTATION_SECRET_KEY  -> base64 of the 32-byte Ed25519 seed
                                  (the backend signer; treat as a secret)
- PACT_ATTESTATION_PUBLIC_KEY  -> "ed25519:<base58>" (for the contract init
                                  args and the public key endpoint)

The same public key is exposed by GET /api/pact/attestation-key and is baked
into the contract at init (pact/scripts/init_contract.py), so every
attestation the backend signs is checkable against it.

Usage:
    python pact/scripts/generate_attestation_key.py

Store the secret in your secret manager (or .env for local runs); never
commit it. A regenerated key invalidates all attestations signed by the old
one, which is fine before deployment and catastrophic after it.
"""

from __future__ import annotations

import argparse
import base64
import secrets
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from pact.attestation import generate_signing_key, public_key_b58  # noqa: E402


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--print-key",
        action="store_true",
        help="Print only the base64 secret key (for scripting).",
    )
    args = parser.parse_args(argv)

    seed = secrets.token_bytes(32)
    signing_key = generate_signing_key(seed)
    secret_b64 = base64.b64encode(seed).decode()

    if args.print_key:
        print(secret_b64)
        return 0

    print("# Settlement attestation keypair (Ed25519)")
    print("# Keep the SECRET private (secret manager / .env, never commit).")
    print(f"export PACT_ATTESTATION_SECRET_KEY={secret_b64}")
    print(f"export PACT_ATTESTATION_PUBLIC_KEY={public_key_b58(signing_key)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
