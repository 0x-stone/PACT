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

from dao.near_rpc import view, near_cli
from dao.sputnik import act_proposal, assert_proposal_status

DEMO_CONTRACT = "pact-demo.testnet"
DAO_ID = "pactdao.testnet"
TEST_ACCOUNTS = [
    "pact-t1-test.testnet",
    "pact-t2-test.testnet",
    "pact-t3-test.testnet",
    "pact-t4-test.testnet",
]

def main():
    prop_id = 13
    challenge_id = "demo-7"
    proposer = "0xgoku.testnet"
    voter = "0xpiccolo.testnet"

    print(f"Checking proposal #{prop_id} on {DAO_ID}...")
    prop = view(DAO_ID, "get_proposal", {"id": prop_id})
    print(f"Proposal status: {prop.get('status')}")

    if prop.get("status") == "InProgress":
        print(f"Voting VoteApprove with {proposer}...")
        try:
            tx_v1 = act_proposal(prop_id, "VoteApprove", sign_as=proposer)
            print(f"  Vote 1 ({proposer}): {tx_v1}")
        except Exception as e:
            print(f"  Vote 1 note: {e}")
        time.sleep(5)

        print(f"Voting VoteApprove with {voter}...")
        try:
            tx_v2 = act_proposal(prop_id, "VoteApprove", sign_as=voter)
            print(f"  Vote 2 ({voter}): {tx_v2}")
        except Exception as e:
            print(f"  Vote 2 note: {e}")
        time.sleep(5)

    assert_proposal_status(prop_id, "Approved")
    print(f"Proposal #{prop_id} APPROVED and executed on-chain!")

    # Enroll test accounts
    for acc in TEST_ACCOUNTS:
        print(f"Enrolling {acc} in {challenge_id}...")
        time.sleep(3)
        try:
            near_cli(DEMO_CONTRACT, "storage_deposit", json.dumps({"account_id": acc}), sign_as=acc, deposit="1250000000000000000000", gas="30.0 Tgas")
        except Exception as e:
            print(f"  Storage deposit note: {e}")
        time.sleep(3)
        try:
            near_cli(DEMO_CONTRACT, "enroll", json.dumps({"challenge_id": challenge_id}), sign_as=acc, deposit="500000000000000000000000", gas="50.0 Tgas")
            print(f"  ✓ {acc} enrolled in {challenge_id}")
        except Exception as e:
            print(f"  Enroll note: {e}")

    ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": challenge_id})
    print(f"\nChallenge {challenge_id} status on chain:")
    print(json.dumps(ch, indent=2))

if __name__ == "__main__":
    main()
