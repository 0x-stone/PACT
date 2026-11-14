#!/usr/bin/env python3
"""
fund_treasury.py — Send NEAR to the DAO treasury.

Usage:
  export NEAR_NETWORK=testnet
  python -m dao.fund_treasury --from 0xgoku.testnet --amount 2

Sends a plain NEAR transfer; no proposal needed.
"""
import argparse, subprocess, sys
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
if str(_REPO) not in sys.path:
    sys.path.insert(0, str(_REPO))

from dao.config import DAO_ID, NEAR_CLI, NETWORK
from dao.near_rpc import view_account, extract_tx_hash

YOCTO = 10**24


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from",    dest="sender",  required=True)
    parser.add_argument("--amount",  type=float,     required=True, help="NEAR to send")
    args = parser.parse_args(argv)

    amount_near = args.amount
    amount_yocto = int(amount_near * YOCTO)

    before = int(view_account(DAO_ID)["amount"]) / YOCTO
    print(f"DAO balance before: {before:.4f} NEAR")

    cmd = [
        NEAR_CLI, "tokens",
        args.sender, "send-near", DAO_ID,
        f"{amount_near} NEAR",
        "network-config", NETWORK,
        "sign-with-keychain", "send",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    output = result.stdout + result.stderr
    print(output)
    if result.returncode != 0:
        raise SystemExit(f"Transfer failed (rc={result.returncode})")

    tx_hash = extract_tx_hash(output)
    after = int(view_account(DAO_ID)["amount"]) / YOCTO
    print(f"DAO balance after : {after:.4f} NEAR")
    print(f"TX: {tx_hash}")
    print(f"Explorer: https://explorer.testnet.near.org/transactions/{tx_hash}")


if __name__ == "__main__":
    main()
