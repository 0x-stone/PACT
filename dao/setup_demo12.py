#!/usr/bin/env python3
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
    challenge_id = "demo-12"
    start_s = 1794597600 # 2026-11-13T19:30:00Z
    duration = 180 # 3 mins (ends at 19:33:00 UTC)
    end_s = start_s + duration
    start_ns = start_s * 1_000_000_000
    end_ns = end_s * 1_000_000_000
    steps = 20

    now_s = int(time.time())
    print(f"Setting up {challenge_id}...")
    print(f"Now:   {datetime.fromtimestamp(now_s, tz=timezone.utc).isoformat()}")
    print(f"Start: {datetime.fromtimestamp(start_s, tz=timezone.utc).isoformat()} ({start_s - now_s}s / {(start_s - now_s)//60} mins from now)")
    print(f"End:   {datetime.fromtimestamp(end_s, tz=timezone.utc).isoformat()} (3 mins duration)")
    print(f"Steps: {steps}")

    # 1. Propose demo-12 to DAO
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
        f"Set demo challenge {challenge_id} ({steps} steps, 1 day, 3 mins)",
        DEMO_CONTRACT,
        "set_next_challenge",
        prop_args,
    )
    print("Submitting proposal to DAO...")
    prop_id, tx_prop = add_proposal(prop, sign_as=proposer)
    print(f"  Proposal ID: {prop_id} (TX: {tx_prop})")
    time.sleep(3)

    print("Voting on proposal...")
    tx_v1 = act_proposal(prop_id, "VoteApprove", sign_as=proposer)
    print(f"  Vote 1 ({proposer}): {tx_v1}")
    time.sleep(3)
    tx_v2 = act_proposal(prop_id, "VoteApprove", sign_as=voter)
    print(f"  Vote 2 ({voter}): {tx_v2}")
    time.sleep(3)

    assert_proposal_status(prop_id, "Approved")
    print("Proposal APPROVED and executed on-chain!")

    # 2. Enroll test accounts immediately
    for acc in TEST_ACCOUNTS:
        print(f"Enrolling {acc} in {challenge_id}...")
        try:
            near_cli(DEMO_CONTRACT, "storage_deposit", json.dumps({"account_id": acc}), sign_as=acc, deposit="1250000000000000000000", gas="30.0 Tgas")
        except Exception as e:
            print(f"  Storage deposit note: {e}")
        time.sleep(2)
        try:
            near_cli(DEMO_CONTRACT, "enroll", json.dumps({"challenge_id": challenge_id}), sign_as=acc, deposit="500000000000000000000000", gas="50.0 Tgas")
            print(f"  ✓ {acc} enrolled in {challenge_id}")
        except Exception as e:
            print(f"  Enroll note: {e}")
        time.sleep(2)

    # 3. Verify enrollment
    ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": challenge_id})
    print(f"\nChallenge {challenge_id} status on chain:")
    print(json.dumps(ch, indent=2))

if __name__ == "__main__":
    main()
