#!/usr/bin/env python3
"""Deploy the Pact NEP-141 contract to NEAR testnet.

Reads pact/rules.json via pact/rules.py, computes rules_hash, and deploys
the contract.  Prints the contract account ID, rules_hash, and transaction
hashes on success.

The deploy script REFUSES to run if:
  - PACT_ATTESTATION_SECRET_KEY is not set (strict mode)
  - PACT_DAO_ACCOUNT_ID is not set or is a placeholder
  - PACT_DEPLOY_ACCOUNT_ID is not set
  - The near-cli binary is not found

Usage:
    export PACT_ATTESTATION_SECRET_KEY=<base64-seed>
    export PACT_DAO_ACCOUNT_ID=<dao>.sputnikv2.testnet
    export PACT_DEPLOY_ACCOUNT_ID=<your-account>.testnet
    python pact/scripts/deploy_contract.py [--dry-run]

Environment variables:
    PACT_ATTESTATION_SECRET_KEY  base64 of 32-byte Ed25519 seed (required)
    PACT_DAO_ACCOUNT_ID          Sputnik DAO account that will control challenges
    PACT_DEPLOY_ACCOUNT_ID       Account used to sign the deploy transaction
    PACT_CONTRACT_ACCOUNT_ID     Defaults to PACT_DEPLOY_ACCOUNT_ID if unset
    NEAR_CLI                     Path to near-cli binary (default: near)
    NEAR_NETWORK                 testnet (default) or mainnet

Security note:
    The deploy account must be funded with enough NEAR to cover:
    - Contract storage (~1 NEAR for the first deploy)
    - Initial FT storage deposit (~0.00125 NEAR for contract self-registration)
    - Enrollment-record storage (~0.00005 NEAR per member)
    Total safe minimum: 2 NEAR for testnet, more for mainnet.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from pact.attestation import public_key_b58, resolve_attestation_public_key  # noqa: E402
from pact.rules import canonical_rules, load_rules, rules_hash  # noqa: E402

WASM_PATH = _REPO_ROOT / "contract" / "target" / "near" / "pact_contract.wasm"
PLACEHOLDER_PREFIXES = ("PLACEHOLDER", "YOUR_", "<", "example")


def _guard_no_placeholder(value: str, name: str) -> None:
    if not value:
        sys.exit(f"ERROR: {name} is not set.  Set it before deploying.")
    for prefix in PLACEHOLDER_PREFIXES:
        if value.upper().startswith(prefix.upper()):
            sys.exit(
                f"ERROR: {name}='{value}' looks like a placeholder.  "
                "Replace it with a real value before deploying."
            )


def build_init_args(rules, attestation_pubkey: str, dao_account_id: str) -> dict:
    """Build the JSON init args for the contract's new() method."""
    h = rules_hash(rules)
    return {
        "attestation_public_key": attestation_pubkey,
        "dao_account_id": dao_account_id,
        "rules_hash": h,
        "stake_yocto": rules.stake_yocto,
        "quorum_pct": rules.quorum_pct,
        "mint_amount": rules.mint_amount,
        "burn_amount": rules.burn_amount,
        "reserve_amount": rules.reserve_amount,
        "min_members": str(rules.min_members),
        "submission_grace_secs": str(rules.submission_grace_secs),
    }


def run(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the init args and deploy command without executing.",
    )
    parser.add_argument(
        "--rules",
        type=Path,
        default=None,
        help="Path to rules.json (default: pact/rules.json).",
    )
    args = parser.parse_args(argv)

    # --- Guard required env vars ---
    attestation_key_b64 = os.getenv("PACT_ATTESTATION_SECRET_KEY", "").strip()
    _guard_no_placeholder(attestation_key_b64, "PACT_ATTESTATION_SECRET_KEY")

    dao_account_id = os.getenv("PACT_DAO_ACCOUNT_ID", "").strip()
    _guard_no_placeholder(dao_account_id, "PACT_DAO_ACCOUNT_ID")
    if not dao_account_id.endswith(".testnet") and not dao_account_id.endswith(".near"):
        print(
            f"WARNING: PACT_DAO_ACCOUNT_ID='{dao_account_id}' does not end in "
            ".testnet or .near.  Continue? [y/N] ",
            end="",
        )
        if input().strip().lower() != "y":
            sys.exit("Aborted.")

    deploy_account = os.getenv("PACT_DEPLOY_ACCOUNT_ID", "").strip()
    _guard_no_placeholder(deploy_account, "PACT_DEPLOY_ACCOUNT_ID")

    contract_account = (
        os.getenv("PACT_CONTRACT_ACCOUNT_ID", "").strip() or deploy_account
    )

    near_cli = os.getenv("NEAR_CLI", "near")
    network = os.getenv("NEAR_NETWORK", "testnet")

    if shutil.which(near_cli) is None and not args.dry_run:
        sys.exit(
            f"ERROR: near-cli binary '{near_cli}' not found.  "
            "Install it: npm install -g near-cli@3.4.2"
        )

    # --- Load and validate rules ---
    rules = load_rules(args.rules)
    attestation_pubkey = resolve_attestation_public_key(strict=True)
    h = rules_hash(rules)
    canon = canonical_rules(rules)

    print(f"=== Pact contract deploy ===")
    print(f"network          : {network}")
    print(f"contract account : {contract_account}")
    print(f"deploy account   : {deploy_account}")
    print(f"dao_account_id   : {dao_account_id}")
    print(f"attestation_key  : {attestation_pubkey}")
    print(f"rules_hash       : {h}")
    print(f"canonical_rules  : {canon}")
    print()

    init_args = build_init_args(rules, attestation_pubkey, dao_account_id)
    init_json = json.dumps(init_args)
    print(f"init args: {json.dumps(init_args, indent=2)}")

    # --- Check WASM exists ---
    if not WASM_PATH.exists() and not args.dry_run:
        sys.exit(
            f"ERROR: WASM not found at {WASM_PATH}.  "
            "Build first: cd contract && cargo near build"
        )

    # --- Build deploy command ---
    # Using near-cli v3 syntax: near deploy
    deploy_cmd = [
        near_cli,
        "deploy",
        contract_account,
        str(WASM_PATH),
        "new",
        init_json,
        "--accountId", deploy_account,
        "--networkId", network,
    ]

    print(f"command: {' '.join(deploy_cmd)}")
    print()

    if args.dry_run:
        print("DRY RUN — not executing.")
        return 0

    result = subprocess.run(deploy_cmd, check=False)
    if result.returncode != 0:
        sys.exit(f"ERROR: deploy failed with exit code {result.returncode}")

    print()
    print("=== Deploy complete ===")
    print(f"contract_id  : {contract_account}")
    print(f"rules_hash   : {h}")
    print()
    print("Next steps:")
    print("  1. Verify: near view <contract> get_rules_hash")
    print(f"     Expected: {h!r}")
    print("  2. Set a challenge via DAO proposal:")
    print("     near call <dao> add_proposal '{...}' --accountId <member> --deposit 0.1")
    print("  3. Members must storage_deposit, then enroll before challenge start.")
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
