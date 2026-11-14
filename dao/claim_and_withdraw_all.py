#!/usr/bin/env python3
"""
claim_and_withdraw_all.py — Claim rewards and withdraw stakes for test accounts in demo-15.
"""
import json
import sys
import time
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_REPO))

from dao.near_rpc import near_cli, view

DEMO_CONTRACT = "pact-demo.testnet"
challenge_id = "demo-15"
TEST_ACCOUNTS = [
    "pact-t1-test.testnet",
    "pact-t2-test.testnet",
    "pact-t3-test.testnet",
    "pact-t4-test.testnet",
]

print(f"=== CHECKING USER 0xgow.testnet IN {challenge_id} ===")
gow_rew = view(DEMO_CONTRACT, "has_claimed_reward", {"challenge_id": challenge_id, "account_id": "0xgow.testnet"})
gow_stk = view(DEMO_CONTRACT, "has_withdrawn_stake", {"challenge_id": challenge_id, "account_id": "0xgow.testnet"})
print(f"0xgow.testnet | has_claimed_reward: {gow_rew} | has_withdrawn_stake: {gow_stk}")

print(f"\n=== CLAIMING REWARDS FOR TEST ACCOUNTS IN {challenge_id} ===")
for acc in TEST_ACCOUNTS:
    print(f"Claiming reward for {acc}...")
    try:
        out = near_cli(
            DEMO_CONTRACT,
            "claim_reward",
            json.dumps({"challenge_id": challenge_id}),
            sign_as=acc,
            gas="50.0 Tgas",
        )
        print(f"  ✓ {acc} reward claimed!")
    except Exception as e:
        print(f"  ! {acc} reward note: {e}")
    time.sleep(2)

print(f"\n=== WITHDRAWING STAKES FOR TEST ACCOUNTS IN {challenge_id} ===")
for acc in TEST_ACCOUNTS:
    print(f"Withdrawing stake for {acc}...")
    try:
        out = near_cli(
            DEMO_CONTRACT,
            "withdraw_stake",
            json.dumps({"challenge_id": challenge_id}),
            sign_as=acc,
            gas="60.0 Tgas",
        )
        print(f"  ✓ {acc} stake withdrawn!")
    except Exception as e:
        print(f"  ! {acc} stake note: {e}")
    time.sleep(2)

print(f"\n=== FINAL MEMBER STATUS FOR {challenge_id} ===")
for acc in ["0xgow.testnet"] + TEST_ACCOUNTS:
    rew = view(DEMO_CONTRACT, "has_claimed_reward", {"challenge_id": challenge_id, "account_id": acc})
    stk = view(DEMO_CONTRACT, "has_withdrawn_stake", {"challenge_id": challenge_id, "account_id": acc})
    print(f"{acc:22} | has_claimed_reward: {rew} | has_withdrawn_stake: {stk}")
