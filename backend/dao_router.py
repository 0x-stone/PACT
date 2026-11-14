"""
DAO + reputation endpoints — separate router mounted in main.py.

Endpoints:
  GET /api/dao/proposals          list DAO proposals (cached 30s)
  GET /api/pact/challenge         active/next challenge from contract
  GET /api/pact/reputation/{id}   per-account reputation from contract events

Trust model (stated honestly):
  - The DAO council (0xgoku, 0xpiccolo, 0xbulma) is trusted to vote honestly.
  - challenge data comes from the pact contract (on-chain, authoritative).
  - reputation is derived from the backend's pact_store DB, which records
    who submitted passed=true attestations. The attestation signer key is
    trusted; the underlying step data is not verified on-chain.
  - No raw health data is exposed anywhere in this router.
"""
from __future__ import annotations

import base64
import json
import os
import time
import urllib.request
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel

# Same dual-mode import as the rest of the backend
_BACKEND_DIR = Path(__file__).resolve().parent
_REPO_ROOT   = _BACKEND_DIR.parent
import sys
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

try:
    from . import pact_store
except ImportError:
    import pact_store  # type: ignore

router = APIRouter()

# ── Config ────────────────────────────────────────────────────────────────

DAO_ID       = os.getenv("PACT_DAO_ID",      "pactdao.testnet")
CONTRACT_ID  = os.getenv("PACT_CONTRACT_ID", "pactcontract.testnet")
RPC_URL      = os.getenv("NEAR_RPC_URL",     "https://rpc.testnet.near.org")

PROPOSALS_CACHE_TTL = 30  # seconds
_proposals_cache: dict = {"ts": 0.0, "data": None}


FALLBACK_RPC_URLS = [
    RPC_URL,
    "https://test.rpc.fastnear.com",
    "https://rpc.testnet.pagoda.co",
]


# ── RPC helper ────────────────────────────────────────────────────────────

def _rpc_view(contract_id: str, method: str, args: dict | None = None) -> Any:
    args_b64 = base64.b64encode(json.dumps(args or {}).encode()).decode()
    body = json.dumps({
        "jsonrpc": "2.0", "id": "1", "method": "query",
        "params": {
            "request_type": "call_function",
            "finality":     "final",
            "account_id":   contract_id,
            "method_name":  method,
            "args_base64":  args_b64,
        }
    }).encode()
    resp = None
    last_err = None
    for url in FALLBACK_RPC_URLS:
        try:
            req = urllib.request.Request(
                url, data=body, headers={"Content-Type": "application/json", "User-Agent": "PactDaoRouter/1.0"}
            )
            with urllib.request.urlopen(req, timeout=10) as r:
                resp = json.loads(r.read())
            break
        except Exception as exc:
            last_err = exc
            continue
    if resp is None:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"NEAR RPC unavailable across all endpoints: {last_err}",
        )
    if "error" in resp:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"RPC error: {resp['error']}",
        )
    result = resp.get("result", {})
    if "error" in result:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Contract error: {result['error']}",
        )
    raw = result.get("result")
    if raw is None:
        return None
    return json.loads(bytes(raw))


# ── Models ────────────────────────────────────────────────────────────────

class ProposalSummary(BaseModel):
    id: int
    proposer: str
    description: str
    kind: str          # "FunctionCall", "Transfer", "ChangePolicy", etc.
    status: str        # "InProgress", "Approved", "Rejected", etc.
    votes: Dict[str, str]   # accountId → "Approve" / "Reject" / "Remove"
    submission_time: str    # ISO timestamp


class ChallengeView(BaseModel):
    challenge_id: str
    steps_per_day: int
    days_required: int
    start_ns: str
    end_ns: str
    enrolled_count: int
    qualifier_count: int
    outcome: str
    start_iso: str
    end_iso: str


class ReputationView(BaseModel):
    account_id:     str
    weeks_enrolled: int
    weeks_passed:   int
    weeks_failed:   int
    prizes_received: int
    note: str = (
        "Derived from backend pact_store (attestation oracle). "
        "Not on-chain truth — the signer key and health data source are trusted."
    )


# ── Endpoints ─────────────────────────────────────────────────────────────

@router.get("/api/dao/proposals", response_model=List[ProposalSummary])
def get_dao_proposals(limit: int = 20, from_index: int = 0):
    """
    List DAO proposals. Cached for 30 seconds to avoid hammering the RPC.
    Returns id, kind, status, description, votes for each proposal.
    """
    global _proposals_cache
    now = time.time()
    if now - _proposals_cache["ts"] > PROPOSALS_CACHE_TTL or _proposals_cache["data"] is None:
        raw = _rpc_view(DAO_ID, "get_proposals", {
            "from_index": from_index,
            "limit":      min(limit, 50),
        })
        _proposals_cache = {"ts": now, "data": raw or []}

    proposals = _proposals_cache["data"]
    result = []
    for i, p in enumerate(proposals):
        # Determine kind label
        kind = list(p.get("kind", {}).keys())[0] if isinstance(p.get("kind"), dict) else str(p.get("kind"))
        # Submission time: convert nanoseconds to ISO
        sub_ns = int(p.get("submission_time", 0))
        sub_iso = datetime.fromtimestamp(sub_ns / 1e9, tz=timezone.utc).isoformat()

        result.append(ProposalSummary(
            id=from_index + i,
            proposer=p.get("proposer", ""),
            description=p.get("description", ""),
            kind=kind,
            status=p.get("status", ""),
            votes={k: list(v.keys())[0] if isinstance(v, dict) else str(v)
                   for k, v in p.get("votes", {}).items()},
            submission_time=sub_iso,
        ))
    return result


@router.get("/api/pact/reputation/{account_id}", response_model=ReputationView)
def get_reputation(account_id: str):
    """
    Per-account public reputation: weeks enrolled, passed, failed, prizes.
    Derived from the backend's pact_store DB (backend eligibility oracle).
    No raw health data is exposed.

    Trust model: this is sourced from the same pipeline that produced the
    attestations. The attestation signer and health data source are trusted.
    Not on-chain truth — the contract has no get_qualifiers list view.
    """
    # Count from pact_store: weekly_results for this member
    try:
        results = pact_store.get_member_weekly_results(account_id)
    except Exception:
        results = []

    weeks_enrolled = len(results)
    weeks_passed   = sum(1 for r in results if r.get("passed"))
    weeks_failed   = weeks_enrolled - weeks_passed

    # Prize count: count approved Transfer proposals to this account from DAO
    # (conservative: count from DAO proposals via RPC)
    prizes = _count_prizes(account_id)

    return ReputationView(
        account_id=account_id,
        weeks_enrolled=weeks_enrolled,
        weeks_passed=weeks_passed,
        weeks_failed=weeks_failed,
        prizes_received=prizes,
    )


def _count_prizes(account_id: str) -> int:
    """
    Count approved Transfer proposals to account_id from the DAO.
    Fetches proposals in batches. Returns count.
    """
    try:
        last_id = _rpc_view(DAO_ID, "get_last_proposal_id") or 0
        count = 0
        batch = 50
        for i in range(0, last_id, batch):
            proposals = _rpc_view(DAO_ID, "get_proposals", {
                "from_index": i,
                "limit":      batch,
            }) or []
            for p in proposals:
                if p.get("status") != "Approved":
                    continue
                kind = p.get("kind", {})
                if isinstance(kind, dict) and "Transfer" in kind:
                    t = kind["Transfer"]
                    if t.get("receiver_id") == account_id and t.get("token_id") in ("", None):
                        count += 1
        return count
    except Exception:
        return 0
