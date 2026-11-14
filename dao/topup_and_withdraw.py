import json, os, subprocess, sys
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_REPO))

NEAR_BIN = "/home/stone/.cargo/bin/near"
os.environ["NEAR_CLI"] = NEAR_BIN

from dao.near_rpc import near_cli

TEST_ACCOUNTS = [
    "pact-t1-test.testnet",
    "pact-t2-test.testnet",
    "pact-t3-test.testnet",
    "pact-t4-test.testnet",
]

DEMO_CONTRACT = "pact-demo.testnet"

# 1. Withdraw stake from demo-10 for each test account
print("Withdrawing stakes from demo-10...")
for acc in TEST_ACCOUNTS:
    try:
        out = near_cli(
            DEMO_CONTRACT,
            "withdraw_stake",
            json.dumps({"challenge_id": "demo-10"}),
            sign_as=acc,
            gas="50.0 Tgas",
        )
        print(f"  ✓ {acc} withdrew stake from demo-10")
    except Exception as e:
        print(f"  ! withdraw note for {acc}: {e}")

# 2. Also send 0.5 NEAR from 0xgoku.testnet to be 100% sure they have plenty of balance
print("\nTopping up test accounts from 0xgoku.testnet...")
for acc in TEST_ACCOUNTS:
    cmd = [
        NEAR_BIN, "tokens", "0xgoku.testnet", "send-near",
        acc, "0.5 NEAR",
        "network-config", "testnet", "sign-with-legacy-keychain", "send"
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0:
        print(f"  ✓ {acc} topped up with 0.5 NEAR")
    else:
        print(f"  ! {acc} top-up note: {res.stderr or res.stdout}")
