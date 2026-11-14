#!/usr/bin/env python3
import json, sys
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_REPO))

from dao.near_rpc import near_cli, view

DEMO_CONTRACT = "pact-demo.testnet"
acc = "pact-t4-test.testnet"
challenge_id = "demo-14"

print(f"Enrolling {acc} in {challenge_id}...")
out = near_cli(
    DEMO_CONTRACT,
    "enroll",
    json.dumps({"challenge_id": challenge_id}),
    sign_as=acc,
    deposit="500000000000000000000000",
    gas="50.0 Tgas",
)
print("Result:", out)

is_en = view(DEMO_CONTRACT, "is_enrolled", {"challenge_id": challenge_id, "account_id": acc})
print(f"{acc} is_enrolled in {challenge_id}: {is_en}")
