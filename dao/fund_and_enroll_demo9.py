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

DEMO_CONTRACT = "pact-demo.testnet"
FUNDER = "0xbulma.testnet"
TEST_ACCOUNTS = [
    "pact-t1-test.testnet",
    "pact-t2-test.testnet",
    "pact-t3-test.testnet",
    "pact-t4-test.testnet",
]

def main():
    challenge_id = "demo-9"
    for acc in TEST_ACCOUNTS:
        print(f"Funding 1 NEAR to {acc} from {FUNDER}...")
        try:
            near_cli(acc, "send_money", None, sign_as=FUNDER, deposit="1.0 NEAR", is_transfer=True)
            print(f"  ✓ Funded {acc}")
        except Exception as e:
            # Fallback to direct near send-money CLI
            print(f"  Transfer via near_cli note: {e}")
            os.system(f"near tokens {FUNDER} send-near {acc} '1.0 NEAR' network-config testnet-fastnear sign-with-keychain send")
        time.sleep(2)

        print(f"Enrolling {acc} in {challenge_id}...")
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
