"""
contract_client.py — Read the pact contract state via NEAR RPC.

This is the single place the backend reads from the on-chain contract.
No writes — the contract is controlled only by the DAO.

The active challenge drives:
  - Which challenge_id string goes into attestation payloads
  - The evaluation window (start_ns → end_ns in nanoseconds)
  - The expiry set on attestations (end_ns/1e9 + grace_secs + EXPIRY_MARGIN_SECS)

Trust model: the contract is authoritative for challenge params.
The backend trusts whatever the DAO has set.
"""
from __future__ import annotations

import base64
import json
import os
import urllib.request
from typing import Any, Optional

RPC_URL     = os.getenv("NEAR_RPC_URL",     "https://rpc.testnet.near.org")
CONTRACT_ID = os.getenv("PACT_CONTRACT_ID", "pactcontract.testnet")

# Attestation expiry margin on top of end_ns/1e9 + submission_grace_secs.
# Ensures expiry > end + grace by a comfortable buffer (1 day).
# Production: consider 7 days. Testnet: 1 day.
EXPIRY_MARGIN_SECS = int(os.getenv("PACT_EXPIRY_MARGIN_SECS", "86400"))


FALLBACK_RPC_URLS = [
    RPC_URL,
    "https://test.rpc.fastnear.com",
    "https://rpc.testnet.pagoda.co",
]


class ContractError(RuntimeError):
    pass


def _rpc_view(method: str, args: dict | None = None, contract_id: Optional[str] = None) -> Any:
    target_contract = contract_id or CONTRACT_ID
    args_b64 = base64.b64encode(json.dumps(args or {}).encode()).decode()
    body = json.dumps({
        "jsonrpc": "2.0", "id": "1", "method": "query",
        "params": {
            "request_type": "call_function",
            "finality":     "final",
            "account_id":   target_contract,
            "method_name":  method,
            "args_base64":  args_b64,
        }
    }).encode()
    resp = None
    last_err = None
    for url in FALLBACK_RPC_URLS:
        try:
            req = urllib.request.Request(
                url, data=body, headers={"Content-Type": "application/json", "User-Agent": "PactBackend/1.0"}
            )
            with urllib.request.urlopen(req, timeout=10) as r:
                resp = json.loads(r.read())
            break
        except Exception as exc:
            last_err = exc
            continue
    if resp is None:
        raise ContractError(f"RPC call failed across all endpoints: {last_err}")
    if "error" in resp:
        raise ContractError(f"RPC error: {resp['error']}")
    result = resp.get("result", {})
    if "error" in result:
        raise ContractError(f"Contract error: {result['error']}")
    raw = result.get("result")
    if raw is None:
        return None
    return json.loads(bytes(raw))


def get_active_challenge(contract_id: Optional[str] = None) -> Optional[dict]:
    """
    Return the active or next challenge from the contract, or None.

    Active = challenge where start_ns <= now_ns < end_ns (in progress).
    Next   = challenge where now_ns < start_ns (upcoming).
    If multiple exist, prefer in-progress over upcoming; among upcoming pick
    the soonest start. Settled/voided challenges are ignored.

    Returns a dict with keys:
      challenge_id, steps_per_day, days_required,
      start_ns (int), end_ns (int), enrolled_count, qualifier_count, outcome
    """
    import time
    challenges = _rpc_view("get_challenges", contract_id=contract_id) or []
    if not challenges:
        return None

    now_ns = int(time.time() * 1_000_000_000)

    active   = None
    upcoming = None

    for ch in challenges:
        outcome = ch.get("outcome", "Pending")
        # Skip settled challenges (not Pending)
        if isinstance(outcome, dict) or outcome not in ("Pending",):
            continue
        start = int(ch["start_ns"])
        end   = int(ch["end_ns"])

        if start <= now_ns < end:
            # In-progress: pick this one
            active = ch
        elif now_ns < start:
            # Upcoming: pick the soonest
            if upcoming is None or start < int(upcoming["start_ns"]):
                upcoming = ch

    # If there is an upcoming challenge whose enrollment is open,
    # and the active challenge has already started (enrollment closed),
    # return the upcoming challenge so members can view and join it.
    if upcoming is not None and (active is None or now_ns >= int(active.get("start_ns", 0))):
        return upcoming

    return active or upcoming


def get_submission_grace_secs(contract_id: Optional[str] = None) -> int:
    """Read submission_grace_secs from the specified contract."""
    val = _rpc_view("get_submission_grace_secs", contract_id=contract_id)
    return int(val) if val is not None else 3600


def attestation_expiry_for_challenge(challenge: dict, contract_id: Optional[str] = None) -> int:
    """
    Compute the attestation expiry (unix seconds) for a contract challenge.

    expiry = floor(end_ns / 1_000_000_000) + submission_grace_secs + EXPIRY_MARGIN_SECS

    This guarantees expiry > end_secs + grace_secs (the contract's requirement)
    by EXPIRY_MARGIN_SECS (default 86400 = 1 day).
    """
    end_secs   = int(challenge["end_ns"]) // 1_000_000_000
    grace_secs = get_submission_grace_secs(contract_id=contract_id)
    return end_secs + grace_secs + EXPIRY_MARGIN_SECS


def challenge_has_ended(challenge: dict) -> bool:
    """True if the challenge end time has passed."""
    import time
    now_ns = int(time.time() * 1_000_000_000)
    return now_ns >= int(challenge["end_ns"])
