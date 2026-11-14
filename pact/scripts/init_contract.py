#!/usr/bin/env python3
"""Validate rules.json and emit init arguments for the pact NEP-141 contract.

Consumer #2 of the single source of truth (pact/rules.json via
pact/rules.py). The Rust contract receives exactly these values in its
``new()`` call; they are immutable afterwards and exposed via view methods.
The Rust side must re-implement the same validation bounds as pact/rules.py —
this script is the Python-side gate before deployment.

Amount fields are already canonical base-10 strings (the NEP-141
convention for U128 arguments), so the output can be piped straight into a
``near call <contract> new '<json>'`` without any unit conversion.

Since the settlement-attestation stage, the init args also include
``attestation_public_key`` ("ed25519:<base58>"): the backend signer's public
key. The contract pins it at init and uses it to verify settlement
attestations (see pact/attestation.py). It is resolved strictly from the
environment — either PACT_ATTESTATION_SECRET_KEY (base64 seed) or
PACT_ATTESTATION_PUBLIC_KEY — because inventing a mismatched key here
would make every backend attestation unverifiable on-chain.

Usage:
    python pact/scripts/init_contract.py                 # JSON to stdout
    python pact/scripts/init_contract.py --out init_args.json

Prerequisites:
    export PACT_ATTESTATION_SECRET_KEY=...   # pact/scripts/generate_attestation_key.py
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Optional

# Allow running this file directly from anywhere by putting the repo root
# (which contains the `pact` package) on sys.path.
_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from pact.attestation import resolve_attestation_public_key  # noqa: E402
from pact.rules import load_rules, rules_hash as compute_rules_hash  # noqa: E402


def build_init_args() -> dict:
    """The validated rules + attestation key + rules_hash, ready for new()."""
    rules = load_rules()
    # model_dump() keeps amount fields as their canonical base-10 strings,
    # which is exactly what near-sdk U128 arguments expect.
    init_args = rules.model_dump()
    init_args["attestation_public_key"] = resolve_attestation_public_key(strict=True)
    init_args["rules_hash"] = compute_rules_hash(rules)
    return init_args


def main(argv: Optional[list] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate pact/rules.json and emit contract init args."
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Write the init args JSON to this file instead of stdout.",
    )
    args = parser.parse_args(argv)

    init_args = build_init_args()
    text = json.dumps(init_args, indent=2)

    if args.out is not None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text + "\n", encoding="utf-8")
        print(
            f"wrote {len(init_args)} validated init args to {args.out}",
            file=sys.stderr,
        )
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
