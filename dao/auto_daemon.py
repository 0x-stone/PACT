#!/usr/bin/env python3
"""
auto_daemon.py — Autonomous lifecycle watcher for demo challenges.

Monitors the challenge until it ends, automatically:
1. Injects test runner verdicts via backend /api/pact/dev/inject for:
   - pact-t1-test.testnet (passed)
   - pact-t2-test.testnet (passed)
   - pact-t3-test.testnet (passed)
   - pact-t4-test.testnet (failed)
2. Submits on-chain completion attestations for the 3 passing test accounts
   during the submission grace period.
3. Once grace expires (end_ns + grace_secs), calls settle_week on pact-demo.testnet.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
if str(_REPO) not in sys.path:
    sys.path.insert(0, str(_REPO))

from dao.config import NETWORK, RPC_URL
from dao.near_rpc import extract_tx_hash, near_cli, view, view_account
from pact.attestation import AttestationPayload, load_signing_key, sign_attestation

DEMO_CONTRACT = "pact-demo.testnet"
BACKEND_URL = os.getenv("BACKEND_URL", "http://127.0.0.1:8000").rstrip("/")
DEV_KEY = os.getenv("HEALTH_SYNC_ADMIN_KEY", "dev-secret-key")
if "PACT_ATTESTATION_SECRET_KEY" not in os.environ:
    os.environ["PACT_ATTESTATION_SECRET_KEY"] = "jmJdO2QPCxip11Y6/TsM3xryA+80k6VS+HqgEar4UPk="

TEST_RUNNERS = [
    ("pact-t1-test.testnet", True, 5),
    ("pact-t2-test.testnet", True, 5),
    ("pact-t3-test.testnet", True, 5),
    ("pact-t4-test.testnet", True, 5),
]


def post_json(url: str, data: dict, headers: dict | None = None) -> dict:
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=req_headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--challenge-id", required=True, help="Challenge ID to watch")
    args = parser.parse_args(argv)
    challenge_id = args.challenge_id

    print(f"\n[DAEMON] Starting watcher for challenge '{challenge_id}' on {DEMO_CONTRACT}...")

    # 1. Fetch challenge info
    ch = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": challenge_id})
    if not ch:
        sys.exit(f"[DAEMON] ERROR: Challenge '{challenge_id}' not found on {DEMO_CONTRACT}.")

    start_ns = int(ch["start_ns"])
    end_ns = int(ch["end_ns"])
    grace_secs = int(view(DEMO_CONTRACT, "get_submission_grace_secs") or 420)
    grace_end_ns = end_ns + grace_secs * 1_000_000_000

    start_dt = datetime.fromtimestamp(start_ns / 1e9, tz=timezone.utc).isoformat()
    end_dt = datetime.fromtimestamp(end_ns / 1e9, tz=timezone.utc).isoformat()
    grace_dt = datetime.fromtimestamp(grace_end_ns / 1e9, tz=timezone.utc).isoformat()

    print(f"[DAEMON] Start : {start_dt}")
    print(f"[DAEMON] End   : {end_dt}")
    print(f"[DAEMON] Grace : {grace_dt} ({grace_secs}s)")

    # 2. Wait until challenge end_ns
    while True:
        now_ns = int(time.time() * 1_000_000_000)
        if now_ns >= end_ns:
            break
        secs_left = int((end_ns - now_ns) / 1e9)
        if secs_left % 30 == 0 or secs_left <= 10:
            print(f"[DAEMON] Challenge in progress... {secs_left}s remaining until end.")
        time.sleep(max(2, min(secs_left, 5)))

    print(f"\n[DAEMON] Challenge end reached! Submission grace window is OPEN.")

    # 3. Inject test verdicts into backend
    print("[DAEMON] Injecting test verdicts into backend...")
    for acc, passed, days in TEST_RUNNERS:
        try:
            res = post_json(
                f"{BACKEND_URL}/api/pact/dev/inject",
                {
                    "account_id": acc,
                    "challenge_id": challenge_id,
                    "passed": passed,
                    "days_completed": days,
                    "contract_id": DEMO_CONTRACT,
                },
                headers={"X-Admin-Key": DEV_KEY, "X-Pact-Profile": "demo"}
            )
            print(f"[DAEMON] ✓ Injected verdict for {acc}: passed={passed}")
        except Exception as e:
            print(f"[DAEMON] ! Warning: inject error for {acc}: {e}")

    # 4. Submit completions for passing test runners
    signing_key = load_signing_key()
    expiry_secs = (end_ns // 1_000_000_000) + grace_secs + 300  # valid past grace

    print("[DAEMON] Submitting completions on-chain for passing test accounts...")
    for acc, passed, _ in TEST_RUNNERS:
        if not passed:
            continue
        try:
            payload = AttestationPayload(
                schema_tag="OPAQUE-PACT-ATTESTATION-V1",
                contract_id=DEMO_CONTRACT,
                challenge_id=challenge_id,
                account_id=acc,
                passed=True,
                expiry=expiry_secs,
            )
            sig_bytes = sign_attestation(signing_key, payload)
            sig_b64 = base64.b64encode(sig_bytes).decode("ascii")

            out = near_cli(
                DEMO_CONTRACT,
                "submit_completion",
                json.dumps({
                    "challenge_id": challenge_id,
                    "account_id": acc,
                    "passed": True,
                    "expiry": str(expiry_secs),
                    "signature": sig_b64,
                }),
                sign_as=acc,
                gas="50.0 Tgas",
            )
            tx = extract_tx_hash(out)
            print(f"[DAEMON] ✓ {acc} submitted completion on-chain (TX: {tx})")
            time.sleep(3)
        except Exception as e:
            print(f"[DAEMON] ! Warning: submit_completion for {acc}: {e}")
            time.sleep(3)

    # 5. Wait for grace period expiration
    while True:
        now_ns = int(time.time() * 1_000_000_000)
        if now_ns >= grace_end_ns:
            break
        secs_left = int((grace_end_ns - now_ns) / 1e9)
        if secs_left % 30 == 0 or secs_left <= 10:
            print(f"[DAEMON] Grace window open... {secs_left}s remaining until settlement.")
        time.sleep(max(2, min(secs_left, 5)))

    print(f"\n[DAEMON] Grace period has EXPIRED. Calling settle_week...")

    # 6. Call settle_week
    try:
        out = near_cli(
            DEMO_CONTRACT,
            "settle_week",
            json.dumps({"challenge_id": challenge_id}),
            sign_as=os.getenv("COUNCIL_PROPOSER", "0xgoku.testnet"),
            gas="100.0 Tgas",
        )
        tx_hash = extract_tx_hash(out)
        print(f"[DAEMON] ✓ settle_week executed successfully! (TX: {tx_hash})")
    except Exception as e:
        print(f"[DAEMON] ! settle_week note: {e}")

    # 7. Print final on-chain outcome
    ch_settled = view(DEMO_CONTRACT, "get_challenge", {"challenge_id": challenge_id})
    print(f"\n[DAEMON] Final Outcome for '{challenge_id}': {ch_settled.get('outcome')}")
    print(f"[DAEMON] Enrolled: {ch_settled.get('enrolled_count')}, Qualifiers: {ch_settled.get('qualifier_count')}")
    print("[DAEMON] Watcher complete.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
