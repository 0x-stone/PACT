#!/usr/bin/env python3
"""
create_1k_movement_pact.py — Create and approve 'The 1K Movement Pact' on-chain via DAO proposal.

Specifications:
- Name: The 1K Movement Pact (ID: demo-1k-movement-pact)
- Contract: pact-demo.testnet (DAO: pactdao.testnet)
- Target: 1,000 steps (1 day)
- Duration: 20 minutes (1,200 seconds)
- Start Time: Exactly 10 minutes (600 seconds) from proposal submission
- Enrolls: All 4 test runners (pact-t1-test, pact-t2-test, pact-t3-test, pact-t4-test)
- User: 0xgow.testnet can join from the mobile app during the 10-minute enrollment window or active window.
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
if str(_REPO) not in sys.path:
    sys.path.insert(0, str(_REPO))

from dao.config import NEAR_CLI, NETWORK
from dao.near_rpc import view, view_account, near_cli
from dao.sputnik import make_function_call_proposal, add_proposal, act_proposal, assert_proposal_status

DEMO_CONTRACT = "pact-demo.testnet"
DAO_ID = "pactdao.testnet"
CHALLENGE_ID = "demo-1k-movement-pact"
STEPS = 1000
DURATION_SECS = 1200 # 20 mins
ENROLLMENT_SECS = 600 # 10 mins

TEST_ACCOUNTS = [
    "pact-t1-test.testnet",
    "pact-t2-test.testnet",
    "pact-t3-test.testnet",
    "pact-t4-test.testnet",
]

def main():
    print("=" * 70)
    print("  CREATING 'The 1K Movement Pact' ON DEMO")
    print("=" * 70)

    # 1. Preflight account checks
    print("\n[Step 1/4] Verifying balances for all accounts...")
    accounts_to_check = ["0xgoku.testnet", "0xpiccolo.testnet"] + TEST_ACCOUNTS
    for acc in accounts_to_check:
        info = view_account(acc)
        near_bal = int(info["amount"]) / 1e24
        print(f"  ✓ {acc:<24}: {near_bal:.4f} NEAR")
        if acc in TEST_ACCOUNTS and near_bal < 0.52:
            sys.exit(f"ERROR: {acc} has insufficient balance ({near_bal:.4f} NEAR). Need >= 0.52 NEAR.")

    # 2. Timing calculation: start in 10 minutes, duration 20 minutes
    now_s = int(time.time())
    start_s = now_s + ENROLLMENT_SECS
    end_s = start_s + DURATION_SECS
    start_ns = start_s * 1_000_000_000
    end_ns = end_s * 1_000_000_000

    print(f"\n[Step 2/4] Challenge Timing & Specifications:")
    print(f"  • Challenge ID      : {CHALLENGE_ID}")
    print(f"  • Challenge Name    : The 1K Movement Pact")
    print(f"  • Submission Time   : {datetime.fromtimestamp(now_s, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
    print(f"  • Start Time (in 10m): {datetime.fromtimestamp(start_s, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
    print(f"  • End Time (+20m)   : {datetime.fromtimestamp(end_s, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
    print(f"  • Target Steps      : {STEPS} steps (1 day)")

    # 3. Create DAO proposal via 0xgoku.testnet
    proposer = "0xgoku.testnet"
    voter = "0xpiccolo.testnet"
    prop_args = {
        "challenge_id": CHALLENGE_ID,
        "steps_per_day": STEPS,
        "days_required": 1,
        "start_ns": str(start_ns),
        "end_ns": str(end_ns),
    }
    prop = make_function_call_proposal(
        f"Set demo challenge The 1K Movement Pact ({STEPS} steps, 1 day, 20 mins)",
        DEMO_CONTRACT,
        "set_next_challenge",
        prop_args,
    )
    print(f"\n[Step 3/4] Submitting DAO proposal to {DAO_ID} via {proposer}...")
    prop_id, tx_prop = add_proposal(prop, sign_as=proposer)
    print(f"  ✓ Proposal ID: {prop_id} (TX: {tx_prop})")
    time.sleep(3)

    print(f"  Voting to approve proposal {prop_id}...")
    tx_v1 = act_proposal(prop_id, "VoteApprove", sign_as=proposer)
    print(f"  ✓ Vote 1 ({proposer}): {tx_v1}")
    time.sleep(3)
    tx_v2 = act_proposal(prop_id, "VoteApprove", sign_as=voter)
    print(f"  ✓ Vote 2 ({voter}): {tx_v2}")
    time.sleep(3)

    assert_proposal_status(prop_id, "Approved")
    print(f"  ✓ Proposal {prop_id} APPROVED and executed on-chain!")

    # 4. Enroll all 4 test accounts
    print(f"\n[Step 4/4] Enrolling all 4 test accounts in '{CHALLENGE_ID}' (0.5 NEAR stake each)...")
    for acc in TEST_ACCOUNTS:
        print(f"  Enrolling {acc}...")
        try:
            out = near_cli(
                DEMO_CONTRACT,
                "enroll",
                json.dumps({"challenge_id": CHALLENGE_ID}),
                sign_as=acc,
                deposit="500000000000000000000000",
                gas="50.0 Tgas"
            )
            print(f"  ✓ {acc} enrolled successfully!")
        except Exception as e:
            print(f"  ! Note on enroll: {e}")
        time.sleep(2)

    # 5. Final On-Chain Verification
    ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": CHALLENGE_ID})
    print("\n" + "=" * 70)
    print("  ON-CHAIN CONFIRMATION:")
    print(f"  • Challenge ID      : {ch.get('challenge_id')}")
    print(f"  • Steps Required    : {ch.get('steps_per_day')}")
    print(f"  • Enrolled Members  : {ch.get('enrolled_count')}")
    print(f"  • Outcome           : {ch.get('outcome')}")
    print("=" * 70)

    for acc in TEST_ACCOUNTS:
        en = view(DEMO_CONTRACT, "is_enrolled", {"challenge_id": CHALLENGE_ID, "account_id": acc})
        print(f"  - {acc:<24}: enrolled={en}")
    print("=" * 70 + "\n")

if __name__ == "__main__":
    main()
