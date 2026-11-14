#!/usr/bin/env python3
import json
import os
import subprocess
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
TEST_ACCOUNTS = [
    "pact-t1-test.testnet",
    "pact-t2-test.testnet",
    "pact-t3-test.testnet",
    "pact-t4-test.testnet",
]

def transfer_near(sender, receiver, amount_near):
    print(f"Transferring {amount_near} NEAR from {sender} to {receiver}...")
    cmd = [
        NEAR_CLI, "tokens",
        sender, "send-near", receiver,
        f"{amount_near} NEAR",
        "network-config", NETWORK,
        "sign-with-keychain", "send",
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"Transfer error: {res.stdout}\n{res.stderr}")
    else:
        print(f"  ✓ Transferred {amount_near} NEAR to {receiver}")
    time.sleep(3)

def main():
    challenge_id = "demo-5"
    # Start at 12:25:19 UTC (end of demo-4)
    start_s = 1794572719
    duration = 1200 # 20 mins
    end_s = start_s + duration
    start_ns = start_s * 1_000_000_000
    end_ns = end_s * 1_000_000_000

    print(f"Setting up {challenge_id}...")
    print(f"Start: {datetime.fromtimestamp(start_s, tz=timezone.utc).isoformat()}")
    print(f"End:   {datetime.fromtimestamp(end_s, tz=timezone.utc).isoformat()}")

    # 1. Top up test accounts from 0xpiccolo.testnet if needed
    for acc in TEST_ACCOUNTS:
        bal = int(view_account(acc)["amount"]) / 1e24
        print(f"{acc} balance: {bal:.3f} NEAR")
        if bal < 0.8:
            transfer_near("0xpiccolo.testnet", acc, 0.5)

    # 2. Propose demo-5 to DAO
    proposer = "0xgoku.testnet"
    voter = "0xpiccolo.testnet"
    prop_args = {
        "challenge_id": challenge_id,
        "steps_per_day": 500,
        "days_required": 1,
        "start_ns": str(start_ns),
        "end_ns": str(end_ns),
    }
    prop = make_function_call_proposal(
        f"Set demo challenge {challenge_id} (500 steps, 1 day)",
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

    # 3. Enroll test accounts
    for acc in TEST_ACCOUNTS:
        print(f"Enrolling {acc}...")
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
        time.sleep(3)

    # 4. Verify enrollment
    ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": challenge_id})
    print(f"\nChallenge {challenge_id} status on chain:")
    print(json.dumps(ch, indent=2))

if __name__ == "__main__":
    main()
