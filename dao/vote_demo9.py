#!/usr/bin/env python3
import json
import os
import sys
import time
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
    prop_id = 15
    proposer = "0xgoku.testnet"
    voter = "0xpiccolo.testnet"

    print(f"Voting on proposal {prop_id}...")
    tx_v1 = act_proposal(prop_id, "VoteApprove", sign_as=proposer)
    print(f"  Vote 1 ({proposer}): {tx_v1}")
    time.sleep(3)
    tx_v2 = act_proposal(prop_id, "VoteApprove", sign_as=voter)
    print(f"  Vote 2 ({voter}): {tx_v2}")
    time.sleep(3)

    assert_proposal_status(prop_id, "Approved")
    print("Proposal 15 APPROVED and executed on-chain!")

    # Enroll test accounts
    challenge_id = "demo-9"
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

    ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": challenge_id})
    print(f"\nChallenge {challenge_id} status on chain:")
    print(json.dumps(ch, indent=2))

if __name__ == "__main__":
    main()
