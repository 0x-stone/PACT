#!/usr/bin/env python3
"""
create_and_enroll_demo15.py — Create and approve demo-15 on-chain via DAO proposal.

Dynamically computes:
  start_time = NOW + 10 mins (600s)
  end_time   = start_time + 5 mins (300s)
  steps      = 20
Enrolls all 4 test runners immediately so user can join and test.
"""
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
from dao.near_rpc import view, near_cli
from dao.sputnik import make_function_call_proposal, add_proposal, act_proposal, assert_proposal_status

DEMO_CONTRACT = "pact-demo.testnet"
DAO_ID = "pactdao.testnet"
TEST_ACCOUNTS = [
    "pact-t1-test.testnet",
    "pact-t2-test.testnet",
    "pact-t3-test.testnet",
    "pact-t4-test.testnet",
]

def main():
    challenge_id = "demo-15"
    steps = 20
    duration = 300 # 5 minutes

    # Pick the start time 10 minutes (600s) from the exact moment of proposal creation
    now_s = int(time.time())
    start_s = now_s + 600
    end_s = start_s + duration
    start_ns = start_s * 1_000_000_000
    end_ns = end_s * 1_000_000_000

    print(f"=== CREATING CHALLENGE {challenge_id} ===")
    print(f"Submission Time : {datetime.fromtimestamp(now_s, tz=timezone.utc).isoformat()}")
    print(f"Start Time      : {datetime.fromtimestamp(start_s, tz=timezone.utc).isoformat()} (in {start_s - now_s}s / {(start_s - now_s)//60} mins)")
    print(f"End Time        : {datetime.fromtimestamp(end_s, tz=timezone.utc).isoformat()} (5 mins duration)")
    print(f"Steps Required  : {steps} steps (1 day)")

    # 1. Propose demo-15 to DAO
    proposer = "0xgoku.testnet"
    voter = "0xpiccolo.testnet"
    prop_args = {
        "challenge_id": challenge_id,
        "steps_per_day": steps,
        "days_required": 1,
        "start_ns": str(start_ns),
        "end_ns": str(end_ns),
    }
    prop = make_function_call_proposal(
        f"Set demo challenge {challenge_id} ({steps} steps, 1 day, 5 mins)",
        DEMO_CONTRACT,
        "set_next_challenge",
        prop_args,
    )
    print("\n[1/3] Submitting proposal to DAO via 0xgoku.testnet...")
    prop_id, tx_prop = add_proposal(prop, sign_as=proposer)
    print(f"  ✓ Proposal ID: {prop_id} (TX: {tx_prop})")
    time.sleep(3)

    print("\n[2/3] Voting on proposal to execute set_next_challenge...")
    tx_v1 = act_proposal(prop_id, "VoteApprove", sign_as=proposer)
    print(f"  ✓ Vote 1 ({proposer}): {tx_v1}")
    time.sleep(3)
    tx_v2 = act_proposal(prop_id, "VoteApprove", sign_as=voter)
    print(f"  ✓ Vote 2 ({voter}): {tx_v2}")
    time.sleep(3)

    assert_proposal_status(prop_id, "Approved")
    print(f"  ✓ Proposal {prop_id} APPROVED and executed on-chain!")

    # 2. Enroll all 4 test accounts immediately
    print("\n[3/3] Enrolling all 4 test accounts in demo-15...")
    for acc in TEST_ACCOUNTS:
        print(f"Enrolling {acc} in {challenge_id}...")
        try:
            near_cli(
                DEMO_CONTRACT,
                "enroll",
                json.dumps({"challenge_id": challenge_id}),
                sign_as=acc,
                deposit="500000000000000000000000",
                gas="50.0 Tgas"
            )
            print(f"  ✓ {acc} enrolled in {challenge_id}")
        except Exception as e:
            print(f"  ! Enroll note: {e}")
        time.sleep(2)

    # 3. Verify on-chain status
    ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": challenge_id})
    print(f"\n=== CHALLENGE {challenge_id} ON-CHAIN STATUS ===")
    print(json.dumps(ch, indent=2))

    for acc in TEST_ACCOUNTS:
        en = view(DEMO_CONTRACT, "is_enrolled", {"challenge_id": challenge_id, "account_id": acc})
        print(f"  {acc:22}: enrolled={en}")

if __name__ == "__main__":
    main()
