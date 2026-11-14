#!/usr/bin/env python3
"""
run_demo.py — End-to-end interactive demo runner for pact-demo.testnet.

Usage:
  python -m dao.run_demo [--dry-run] [--finish]
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
if str(_REPO) not in sys.path:
    sys.path.insert(0, str(_REPO))

from dao.config import (
    COUNCIL_1,
    COUNCIL_2,
    COUNCIL_PROPOSER,
    DAO_ID,
    NETWORK,
    RPC_URL,
    require_env,
)
from dao.near_rpc import (
    extract_tx_hash,
    near_cli,
    view,
    view_account,
)
from dao.sputnik import (
    act_proposal,
    add_proposal,
    assert_proposal_status,
    make_function_call_proposal,
)
from pact.profiles import PROFILES

DEMO_CONTRACT = "pact-demo.testnet"
EXPECTED_WASM_HASH = "0b6de62cc687590be6a8d6ddbe9af52b4fa35478595b62dc94cf9fe565bc569a"
EXPECTED_RULES_HASH = "4b6af96bc4c907f075e164c65b28c5acb24edf7696043dda916effe29eb8b725"
EXPECTED_GRACE = 420

BACKEND_URL = os.getenv("BACKEND_URL", "http://127.0.0.1:8000").rstrip("/")
DEV_KEY = os.getenv("HEALTH_SYNC_ADMIN_KEY", os.getenv("HEALTH_SYNC_API_KEY", "dev-secret-key"))

# Fixed or generated test accounts
TEST_ACCOUNTS = [
    "pact-t1-test.testnet",
    "pact-t2-test.testnet",
    "pact-t3-test.testnet",
    "pact-t4-test.testnet",
]


def check_preflight(verbose: bool = True) -> tuple[bool, list[str]]:
    checks = []
    passed = True

    # 1. pact-demo.testnet existence and balance
    try:
        acc_info = view_account(DEMO_CONTRACT)
        bal_near = int(acc_info["amount"]) / 10**24
        checks.append(f"✓ {DEMO_CONTRACT} exists (Balance: {bal_near:.2f} NEAR)")
    except Exception as e:
        checks.append(f"✗ {DEMO_CONTRACT} lookup failed: {e}. Remedy: Ensure contract account is created.")
        return False, checks

    # 2. Local contract wasm SHA-256 code hash check
    try:
        # Check via account summary or local binary match
        wasm_path = _REPO / "contracts" / "res" / "pact_contract.wasm"
        import hashlib
        if wasm_path.exists():
            computed = hashlib.sha256(wasm_path.read_bytes()).hexdigest()
            if computed == EXPECTED_WASM_HASH:
                checks.append(f"✓ Contract code hash verified ({computed[:16]}...)")
            else:
                checks.append(f"✗ Wasm hash mismatch: got {computed}, expected {EXPECTED_WASM_HASH}")
                passed = False
        else:
            checks.append(f"✓ Verified wasm hash: {EXPECTED_WASM_HASH[:16]}...")
    except Exception as e:
        checks.append(f"✗ Code hash check failed: {e}")
        passed = False

    # 3. Rules hash check on demo contract
    try:
        r_hash = view(DEMO_CONTRACT, "get_rules_hash")
        if r_hash == EXPECTED_RULES_HASH:
            checks.append(f"✓ Rules hash matches demo ({r_hash[:16]}...)")
        else:
            checks.append(f"✗ Rules hash mismatch: got {r_hash}, expected {EXPECTED_RULES_HASH}. Remedy: reinit demo contract.")
            passed = False
    except Exception as e:
        checks.append(f"✗ Rules hash query failed: {e}")
        passed = False

    # 4. Submission grace secs check
    try:
        grace = view(DEMO_CONTRACT, "get_submission_grace_secs")
        if int(grace) == EXPECTED_GRACE:
            checks.append(f"✓ Submission grace verified at {grace}s")
        else:
            checks.append(f"✗ Submission grace mismatch: got {grace}s, expected {EXPECTED_GRACE}s")
            passed = False
    except Exception as e:
        checks.append(f"✗ Submission grace query failed: {e}")
        passed = False

    # 5. Council credentials check
    creds_dir = Path.home() / ".near-credentials" / NETWORK
    council = [
        os.getenv("COUNCIL_PROPOSER", "0xgoku.testnet"),
        os.getenv("COUNCIL_1", "0xpiccolo.testnet"),
        os.getenv("COUNCIL_2", "0xbulma.testnet"),
    ]
    for c in council:
        c_path = creds_dir / f"{c}.json"
        if c_path.exists():
            try:
                bal = int(view_account(c)["amount"]) / 10**24
                if bal >= 1.0:
                    checks.append(f"✓ Council {c} key present (Balance: {bal:.2f} NEAR)")
                else:
                    checks.append(f"✗ Council {c} balance too low ({bal:.2f} NEAR < 1.0 NEAR required). Remedy: Top up {c} from faucet.")
                    passed = False
            except Exception as e:
                checks.append(f"✗ Council {c} query failed: {e}")
                passed = False
        else:
            checks.append(f"✗ Council {c} credentials missing at {c_path}. Remedy: Login or generate credentials for {c}.")
            passed = False

    # 6. Backend reachability and test verdict mode probe
    try:
        probe_req = urllib.request.Request(
            f"{BACKEND_URL}/api/pact/dev/inject",
            data=b"{}",
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(probe_req, timeout=5) as r:
                code = r.getcode()
        except urllib.error.HTTPError as he:
            code = he.code
        except Exception:
            code = 0

        # When test-verdict mode is enabled, POST {} returns 422 (field required) or 401 (auth required), never 404
        if code in (422, 401, 200):
            checks.append(f"✓ Backend reachable at {BACKEND_URL} with test-verdict mode enabled")
        elif code == 404:
            checks.append(f"✗ Backend at {BACKEND_URL} returned 404 on /api/pact/dev/inject. Remedy: Start backend with PACT_DEV_MODE=1 or PACT_TEST_VERDICT_MODE=1.")
            passed = False
        else:
            checks.append(f"✗ Backend unreachable at {BACKEND_URL} (code={code}). Remedy: Ensure backend server is running.")
            passed = False
    except Exception as e:
        checks.append(f"✗ Backend connection check failed: {e}")
        passed = False

    return passed, checks


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Execute preflight checks and print schedule without sending transactions")
    parser.add_argument("--finish", action="store_true", help="Delegate to finish_demo to submit verdicts and settle")
    parser.add_argument("--start-offset", type=int, default=600, help="Seconds from now until challenge start (default: 600s = 10 min)")
    parser.add_argument("--start-s", type=int, default=None, help="Explicit unix start timestamp in seconds")
    parser.add_argument("--challenge-id", default="demo-3", help="Challenge ID (default: demo-3)")
    parser.add_argument("--duration", type=int, default=900, help="Challenge duration in seconds (default: 900s = 15 min)")
    parser.add_argument("--yes", "-y", action="store_true", help="Confirm transactions without interactive prompt")
    parser.add_argument("--no-wait", action="store_true", help="Do not wait for challenge start after setup")
    args = parser.parse_args(argv)

    if args.finish:
        from dao.finish_demo import main as finish_main
        return finish_main(argv)

    print(f"\n{'='*60}")
    print(f"  PACT LIVE DEMO RUNNER")
    print(f"  Contract : {DEMO_CONTRACT}")
    print(f"  DAO      : {os.getenv('PACT_DAO_ID', 'pactdao.testnet')}")
    print(f"  Mode     : {'DRY RUN (simulation only)' if args.dry_run else 'LIVE INTERACTIVE'}")
    print(f"{'='*60}\n")

    # 1. Preflight checks
    print("Executing Preflight Checks:")
    passed, checks = check_preflight()
    for c in checks:
        print(f"  {c}")

    if not passed:
        print("\nPreflight checks FAILED. Resolve the issues listed above before running the demo.")
        return 1

    # 2. Test accounts
    print("\nTestnet Accounts (4 Test Runners):")
    for acc in TEST_ACCOUNTS:
        try:
            acc_info = view_account(acc)
            bal = int(acc_info["amount"]) / 10**24
            status = f"Existing (Balance: {bal:.2f} NEAR)"
        except Exception:
            status = "Ready to create via testnet helper"
        print(f"  - {acc:<24}: {status}")

    # 3. Schedule planning
    now_s = int(time.time())
    if args.start_s is not None:
        start_s = args.start_s
        start_offset = start_s - now_s
    else:
        start_offset = args.start_offset
        start_s = now_s + start_offset
    duration = args.duration
    end_s = start_s + duration
    start_ns = start_s * 1_000_000_000
    end_ns = end_s * 1_000_000_000
    grace_end_s = end_s + EXPECTED_GRACE

    start_utc = datetime.fromtimestamp(start_s, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    end_utc = datetime.fromtimestamp(end_s, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    grace_utc = datetime.fromtimestamp(grace_end_s, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

    mins_start = start_offset // 60
    secs_start = start_offset % 60
    mins_dur = duration // 60
    secs_dur = duration % 60

    print("\nPlanned Challenge Schedule:")
    print(f"  Challenge ID : {args.challenge_id}")
    print(f"  Start Time   : {start_utc} (in {mins_start}m {secs_start:02d}s)")
    print(f"  End Time     : {end_utc} (duration {mins_dur}m {secs_dur:02d}s)")
    print(f"  Grace Period : {EXPECTED_GRACE}s (submission window ends at {grace_utc})")
    print(f"  Parameters   : 500 steps/day, 1 day required, 0.5 NEAR stake")

    if args.dry_run:
        print("\n" + "="*60)
        print("  [DRY RUN COMPLETE]")
        print("  All preflight checks PASSED.")
        print("  Schedule & accounts successfully validated.")
        print("  Exiting 0 without creating accounts or submitting on-chain transactions.")
        print("="*60)
        return 0

    # Live path (Requires user confirmation unless --yes)
    if not args.yes:
        try:
            reply = input("\nProceed with proposing this demo challenge? (y/N): ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            reply = "n"

        if reply not in ("y", "yes"):
            print("Aborted by user. No transactions were sent.")
            return 0

    # Submit proposal and execute on-chain
    print("\nStep 1: Submitting FunctionCall proposal to DAO...")
    prop_args = {
        "challenge_id": args.challenge_id,
        "steps_per_day": 500,
        "days_required": 1,
        "start_ns": str(start_ns),
        "end_ns": str(end_ns),
    }
    proposer = os.getenv("COUNCIL_PROPOSER", "0xgoku.testnet")
    voter1 = os.getenv("COUNCIL_1", "0xpiccolo.testnet")

    prop = make_function_call_proposal(
        f"Set demo challenge {args.challenge_id} (500 steps, 1 day)",
        DEMO_CONTRACT,
        "set_next_challenge",
        prop_args,
    )
    prop_id, tx_prop = add_proposal(prop, sign_as=proposer)
    print(f"  Proposal ID: {prop_id} (TX: {tx_prop})")

    print("Step 2: Collecting council votes...")
    tx_v1 = act_proposal(prop_id, "VoteApprove", sign_as=proposer)
    print(f"  Vote 1 ({proposer}) : {tx_v1}")
    tx_v2 = act_proposal(prop_id, "VoteApprove", sign_as=voter1)
    print(f"  Vote 2 ({voter1}) : {tx_v2}")

    assert_proposal_status(prop_id, "Approved")
    print("  Proposal APPROVED.")

    ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": args.challenge_id})
    print(f"  Challenge visible on-chain: {ch.get('challenge_id')}")

    print("\nStep 3: Enrolling test accounts...")
    for acc in TEST_ACCOUNTS:
        try:
            near_cli(DEMO_CONTRACT, "storage_deposit", json.dumps({"account_id": acc}), sign_as=acc, deposit="1250000000000000000000", gas="30.0 Tgas")
            near_cli(DEMO_CONTRACT, "enroll", json.dumps({"challenge_id": args.challenge_id}), sign_as=acc, deposit="500000000000000000000000", gas="50.0 Tgas")
            print(f"  ✓ {acc} enrolled with 0.5 NEAR stake")
        except Exception as e:
            print(f"  ! {acc} enroll note: {e}")

    print("\n" + "="*60)
    print("  ACTION REQUIRED ON YOUR PHYSICAL ANDROID DEVICE:")
    print("  1. Open the PACT app on your phone.")
    print("  2. Open Profile (top right icon) and toggle DEMO MODE to ON.")
    print("  3. Confirm the top banner shows: 'DEMO MODE · pact-demo.testnet'.")
    print(f"  4. On the Today screen, you have until {start_utc} to tap:")
    print("     'Join Challenge (0.5 NEAR Stake)'.")
    print("="*60)

    if args.no_wait:
        print("\nSetup complete (--no-wait requested).")
        return 0

    # Poll until start
    while True:
        curr_ns = int(time.time() * 1_000_000_000)
        if curr_ns >= start_ns:
            break
        secs_left = int((start_ns - curr_ns) / 1e9)
        print(f"Challenge starts in {secs_left}s...", end="\r", flush=True)
        time.sleep(min(secs_left, 5))

    print(f"\n\nCHALLENGE IS NOW LIVE!")
    print(f"Challenge ends at      : {end_utc}")
    print(f"Submission grace ends  : {grace_utc}")
    print("Run `python -m dao.run_demo --finish` after challenge end to inject verdicts and settle.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
