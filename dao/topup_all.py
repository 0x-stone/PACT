#!/usr/bin/env python3
import json, os, subprocess, sys, time

NEAR_BIN = "/home/stone/.cargo/bin/near"

transfers = [
    ("0xgoku.testnet", "2.0 NEAR"),
    ("0xpiccolo.testnet", "1.0 NEAR"),
    ("0xbulma.testnet", "1.0 NEAR"),
    ("pact-t1-test.testnet", "0.5 NEAR"),
    ("pact-t2-test.testnet", "0.5 NEAR"),
    ("pact-t3-test.testnet", "0.5 NEAR"),
    ("pact-t4-test.testnet", "0.5 NEAR"),
]

print("=== TOPPING UP ACCOUNTS FROM 0xstone.testnet ===")
for receiver, amt in transfers:
    cmd = [
        NEAR_BIN, "tokens", "0xstone.testnet", "send-near",
        receiver, amt,
        "network-config", "testnet",
        "sign-with-legacy-keychain", "send"
    ]
    print(f"Sending {amt} to {receiver}...")
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0:
        print(f"  ✓ Sent {amt} to {receiver}")
    else:
        print(f"  ! Error: {res.stderr or res.stdout}")
    time.sleep(2)

print("\n=== TOPUP COMPLETE ===")
