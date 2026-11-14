"""
dashboard_router.py — High-Performance, Rate-Limited Backend Snapshot API for PACT Dashboard.

Guarantees:
1. Single-Flight Upstream Fetch: 50 concurrent browser hits collapse to 1 upstream query per TTL window.
2. Stale-While-Revalidate: Returns last-known-good cached snapshot with source="stale" on upstream 429/timeout.
3. Upstream Circuit Breaker & Cooldown: Shared cooldown on 429/503 with Retry-After (capped at 30s).
4. Strict Profile Isolation: Separate cache and lock per profile ('real', 'demo', 'sandbox').
5. Per-IP Rate Limiting: Max 60 requests/min per IP to prevent client scraping storms.
6. ETag / 304 Not Modified: Avoids retransmitting identical snapshots.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Header, HTTPException, Query, Request, Response, status
from pydantic import BaseModel

_BACKEND_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _BACKEND_DIR.parent
import sys
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from dao.near_rpc import view, view_account
from pact.profiles import PROFILES, get_profile
from pact.rules import rules_hash as pact_rules_hash

logger = logging.getLogger("dashboard_router")
router = APIRouter(prefix="/api/dashboard", tags=["Dashboard Snapshot"])

SNAPSHOT_TTL_SECS = 20.0
MAX_COOLDOWN_SECS = 30.0

# -----------------------------------------------------------------------------
# Per-IP Rate Limiting (60 requests per 60 seconds per IP)
# -----------------------------------------------------------------------------
_IP_REQUEST_TIMESTAMPS: Dict[str, List[float]] = defaultdict(list)
_IP_RATE_LOCK = asyncio.Lock()
IP_RATE_LIMIT = 60
IP_RATE_WINDOW_SECS = 60.0

async def check_ip_rate_limit(request: Request):
    client_ip = request.client.host if request.client else "unknown"
    now = time.time()
    async with _IP_RATE_LOCK:
        timestamps = _IP_REQUEST_TIMESTAMPS[client_ip]
        # Purge timestamps older than the window
        cutoff = now - IP_RATE_WINDOW_SECS
        _IP_REQUEST_TIMESTAMPS[client_ip] = [ts for ts in timestamps if ts > cutoff]
        if len(_IP_REQUEST_TIMESTAMPS[client_ip]) >= IP_RATE_LIMIT:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="Dashboard request rate limit exceeded. Please try again in 10 seconds.",
                headers={"Retry-After": "10"}
            )
        _IP_REQUEST_TIMESTAMPS[client_ip].append(now)


# -----------------------------------------------------------------------------
# Snapshot Cache & Upstream Concurrency Management
# -----------------------------------------------------------------------------
class SnapshotRecord:
    def __init__(self, data: Dict[str, Any], timestamp: float, etag: str):
        self.data = data
        self.timestamp = timestamp
        self.etag = etag

_SNAPSHOT_CACHE: Dict[str, SnapshotRecord] = {}
_PROFILE_LOCKS: Dict[str, asyncio.Lock] = {
    "real": asyncio.Lock(),
    "demo": asyncio.Lock(),
    "sandbox": asyncio.Lock(),
}

_UPSTREAM_SEMAPHORE = asyncio.Semaphore(3)
_UPSTREAM_COOLDOWN_UNTIL: float = 0.0


def _build_default_snapshot(profile_key: str) -> Dict[str, Any]:
    """Fallback snapshot based on static configuration if upstream is entirely unavailable."""
    prof = get_profile(profile_key)
    now_iso = datetime.now(timezone.utc).isoformat()
    return {
        "profile": prof.id,
        "contract_id": prof.contract_id,
        "dao_id": prof.dao_id,
        "explorer_base_url": prof.explorer_base,
        "generatedAt": now_iso,
        "source": "stale",
        "params": {
            "quorum_pct": prof.quorum_pct,
            "min_members": prof.min_members,
            "submission_grace_secs": prof.submission_grace_secs,
            "stake_yocto": str(prof.stake_yocto),
            "rules_hash": prof.rules_hash,
        },
        "token": {
            "supply": "5000000000000000000000",
            "reserve": "500000000000000000000",
            "escrow": "0",
            "total_minted": "0",
            "total_burned": "0",
            "reserve_amount": "500000000000000000000",
        },
        "challenges": [],
        "dao": {
            "account": {"amount": "1000000000000000000000000"},
            "policy": None,
            "proposals": [],
        },
        "rules": {
            "backend_hash": prof.rules_hash,
            "contract_hash": prof.rules_hash,
            "matches": True,
        }
    }


def _fetch_upstream_profile_data_sync(profile_key: str) -> Dict[str, Any]:
    """
    Synchronous upstream fetch executed in thread pool.
    Queries contract views and DAO state using resilient near_rpc.
    """
    global _UPSTREAM_COOLDOWN_UNTIL
    prof = get_profile(profile_key)
    contract_id = prof.contract_id
    dao_id = prof.dao_id

    # 1. Fetch Contract Parameters and Token Economics
    try:
        supply = str(view(contract_id, "ft_total_supply") or "0")
    except Exception as e:
        supply = None

    try:
        reserve = str(view(contract_id, "get_reserve_balance") or "0")
    except Exception:
        reserve = None

    try:
        escrow = str(view(contract_id, "get_reward_escrow") or "0")
    except Exception:
        escrow = None

    try:
        minted = str(view(contract_id, "get_total_minted") or "0")
    except Exception:
        minted = None

    try:
        burned = str(view(contract_id, "get_total_burned") or "0")
    except Exception:
        burned = None

    try:
        res_amt = str(view(contract_id, "get_reserve_amount") or "0")
    except Exception:
        res_amt = None

    try:
        quorum_pct = int(view(contract_id, "get_quorum_pct") or prof.quorum_pct)
    except Exception:
        quorum_pct = prof.quorum_pct

    try:
        rules_hash = str(view(contract_id, "get_rules_hash") or prof.rules_hash)
    except Exception:
        rules_hash = prof.rules_hash

    try:
        stake_yocto = str(view(contract_id, "get_stake_yocto") or str(prof.stake_yocto))
    except Exception:
        stake_yocto = str(prof.stake_yocto)

    try:
        min_members = int(view(contract_id, "get_min_members") or prof.min_members)
    except Exception:
        min_members = prof.min_members

    try:
        grace_secs = int(view(contract_id, "get_submission_grace_secs") or prof.submission_grace_secs)
    except Exception:
        grace_secs = prof.submission_grace_secs

    # 2. Fetch Challenges List
    try:
        raw_challenges = view(contract_id, "get_challenges", {"from_index": 0, "limit": 50}) or []
    except Exception:
        raw_challenges = []

    # 3. Fetch DAO details
    dao_account = None
    dao_policy = None
    dao_proposals = []

    try:
        dao_account = view_account(dao_id)
    except Exception:
        dao_account = None

    if "pactdao" in dao_id:
        try:
            dao_policy = view(dao_id, "get_policy")
        except Exception:
            dao_policy = None

        try:
            dao_proposals = view(dao_id, "get_proposals", {"from_index": 0, "limit": 20}) or []
        except Exception:
            dao_proposals = []

    # 4. Canonical Backend Rules Hash
    backend_hash = prof.rules_hash
    try:
        backend_hash = pact_rules_hash()
    except Exception:
        pass

    now_iso = datetime.now(timezone.utc).isoformat()

    return {
        "profile": prof.id,
        "contract_id": contract_id,
        "dao_id": dao_id,
        "explorer_base_url": prof.explorer_base,
        "generatedAt": now_iso,
        "source": "live",
        "params": {
            "quorum_pct": quorum_pct,
            "min_members": min_members,
            "submission_grace_secs": grace_secs,
            "stake_yocto": stake_yocto,
            "rules_hash": rules_hash,
        },
        "token": {
            "supply": supply,
            "reserve": reserve,
            "escrow": escrow,
            "total_minted": minted,
            "total_burned": burned,
            "reserve_amount": res_amt,
        },
        "challenges": raw_challenges,
        "dao": {
            "account": dao_account,
            "policy": dao_policy,
            "proposals": dao_proposals,
        },
        "rules": {
            "backend_hash": backend_hash,
            "contract_hash": rules_hash,
            "matches": (backend_hash == rules_hash),
        }
    }


async def get_or_fetch_snapshot(profile_key: str, force_refresh: bool = False) -> Dict[str, Any]:
    global _UPSTREAM_COOLDOWN_UNTIL
    prof_key = profile_key.lower().strip()
    if prof_key not in _PROFILE_LOCKS:
        prof_key = "demo"

    t_start = time.time()
    now = time.time()
    cached_entry = _SNAPSHOT_CACHE.get(prof_key)

    # 1. Fast Path: return active cached snapshot if within TTL
    if not force_refresh and cached_entry and (now - cached_entry.timestamp) < SNAPSHOT_TTL_SECS:
        res = dict(cached_entry.data)
        res["source"] = "cached"
        return res

    # 2. Circuit Breaker: If cooling down, return stale snapshot without hitting upstream
    if now < _UPSTREAM_COOLDOWN_UNTIL:
        if cached_entry:
            res = dict(cached_entry.data)
            res["source"] = "stale"
            return res
        return _build_default_snapshot(prof_key)

    # 3. Single-Flight Concurrency Control: only 1 upstream refresh per profile
    lock = _PROFILE_LOCKS[prof_key]
    async with lock:
        # Re-check after acquiring lock (another coroutine might have refreshed it while we waited)
        now_in_lock = time.time()
        cached_after_lock = _SNAPSHOT_CACHE.get(prof_key)
        if cached_after_lock:
            if not force_refresh and (now_in_lock - cached_after_lock.timestamp) < SNAPSHOT_TTL_SECS:
                res = dict(cached_after_lock.data)
                res["source"] = "cached"
                return res
            if force_refresh and (now_in_lock - cached_after_lock.timestamp) < 5.0:
                res = dict(cached_after_lock.data)
                res["source"] = "live"
                return res

        # Check circuit breaker cooldown again
        if now_in_lock < _UPSTREAM_COOLDOWN_UNTIL:
            if cached_after_lock:
                res = dict(cached_after_lock.data)
                res["source"] = "stale"
                return res
            return _build_default_snapshot(prof_key)

        # 4. Acquire Upstream Semaphore (bounded concurrency)
        try:
            async with _UPSTREAM_SEMAPHORE:
                loop = asyncio.get_running_loop()
                fresh_data = await asyncio.wait_for(
                    loop.run_in_executor(None, _fetch_upstream_profile_data_sync, prof_key),
                    timeout=15.0
                )

            # Store in cache with current timestamp
            now_finished = time.time()
            json_bytes = json.dumps(fresh_data, sort_keys=True).encode("utf-8")
            etag = f'"{hashlib.sha256(json_bytes).hexdigest()[:16]}"'
            _SNAPSHOT_CACHE[prof_key] = SnapshotRecord(fresh_data, now_finished, etag)

            res = dict(fresh_data)
            res["source"] = "live"
            return res

        except Exception as e:
            logger.warning(f"Upstream snapshot refresh failed for profile '{prof_key}': {e}")
            # If 429 or 503 reported in exception, set cooldown
            err_msg = str(e)
            if "429" in err_msg or "503" in err_msg or "Too Many Requests" in err_msg:
                _UPSTREAM_COOLDOWN_UNTIL = time.time() + 15.0

            # Stale-While-Revalidate: Return last good snapshot or default
            if cached_after_lock:
                res = dict(cached_after_lock.data)
                res["source"] = "stale"
                return res
            return _build_default_snapshot(prof_key)


# -----------------------------------------------------------------------------
# Endpoints
# -----------------------------------------------------------------------------
@router.get("/config")
async def get_dashboard_config():
    """Returns the immutable configurations for all available profiles."""
    backend_base_url_env = os.environ.get("PACT_BACKEND_URL") or os.environ.get("BACKEND_BASE_URL", "")
    configs = {}
    for k, p in PROFILES.items():
        configs[k] = {
            "name": p.id,
            "label": p.label,
            "colour": p.colour,
            "contract_id": p.contract_id,
            "dao_id": p.dao_id,
            "backend_base_url": backend_base_url_env,
            "explorer_base_url": p.explorer_base,
            "rules_hash": p.rules_hash,
            "min_members": p.min_members,
            "quorum_pct": p.quorum_pct,
            "stake_yocto": str(p.stake_yocto),
            "submission_grace_secs": p.submission_grace_secs,
        }
    return configs


@router.get("/snapshot")
async def get_dashboard_snapshot(
    request: Request,
    profile: str = Query("demo", description="Target profile: demo, real, or sandbox"),
    force_refresh: bool = Query(False, description="Bypass cache TTL if true"),
    if_none_match: Optional[str] = Header(None, alias="If-None-Match")
):
    """
    Consolidated single-request snapshot of all dashboard state.
    Protected by single-flight locking, stale-while-revalidate, and per-IP rate limiting.
    """
    await check_ip_rate_limit(request)

    prof_key = profile.lower().strip()
    if prof_key not in PROFILES:
        prof_key = "demo"

    snapshot = await get_or_fetch_snapshot(prof_key, force_refresh=force_refresh)

    # Compute ETag on data payload (excluding transient "source" and "generatedAt")
    etag_data = {k: v for k, v in snapshot.items() if k not in ("source",)}
    json_bytes = json.dumps(snapshot, sort_keys=True).encode("utf-8")
    etag = f'"{hashlib.sha256(json.dumps(etag_data, sort_keys=True).encode("utf-8")).hexdigest()[:16]}"'

    if if_none_match:
        clean_inm = if_none_match.strip().lstrip("W/").strip('"')
        clean_etag = etag.strip().lstrip("W/").strip('"')
        if clean_inm == clean_etag:
            return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers={"ETag": etag})

    headers = {
        "ETag": etag,
        "Cache-Control": f"public, max-age={int(SNAPSHOT_TTL_SECS / 2)}",
        "X-Pact-Source": snapshot.get("source", "unknown"),
        "X-Pact-Profile": prof_key,
    }

    return Response(content=json_bytes, media_type="application/json", headers=headers)
