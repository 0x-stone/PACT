#!/usr/bin/env python3
import json, sys, time
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

print(f"=== ENROLLING ALL 4 TEST RUNNERS IN {challenge_id} ===")
for acc in TEST_ACCOUNTS:
    print(f"Enrolling {acc}...")
    try:
        out = near_cli(
            DEMO_CONTRACT,
            "enroll",
            json.dumps({"challenge_id": challenge_id}),
            sign_as=acc,
            deposit="500000000000000000000000",
            gas="50.0 Tgas",
        )
        print(f"  ✓ {acc} enrolled successfully!")
    except Exception as e:
        print(f"  ! {acc} error: {e}")
    time.sleep(2)

print("\n=== VERIFYING ENROLLMENTS ===")
ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": challenge_id})
print("Challenge on-chain:", json.dumps(ch, indent=2))
for acc in TEST_ACCOUNTS:
    en = view(DEMO_CONTRACT, "is_enrolled", {"challenge_id": challenge_id, "account_id": acc})
    print(f"  {acc:22}: enrolled={en}")
