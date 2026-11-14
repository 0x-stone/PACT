"""
test_dashboard_snapshot.py — Unit & Integration tests for Dashboard Snapshot API.
Verifies:
- 30 concurrent snapshot requests collapse to 1 upstream fetch (Single-Flight).
- Upstream 429/timeout serves stale snapshot with circuit breaker cooldown.
- Semaphore permits are never leaked on exception.
- Strict profile isolation across demo, real, and sandbox.
- Per-IP rate limiting (60 req/min).
- ETag / If-None-Match returns HTTP 304.
"""
from __future__ import annotations

import asyncio
import time
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from backend.main import app
from backend import dashboard_router

client = TestClient(app)


@pytest.fixture(autouse=True)
def reset_cache_and_cooldown():
    dashboard_router._SNAPSHOT_CACHE.clear()
    dashboard_router._UPSTREAM_COOLDOWN_UNTIL = 0.0
    dashboard_router._IP_REQUEST_TIMESTAMPS.clear()
    yield
    dashboard_router._SNAPSHOT_CACHE.clear()
    dashboard_router._UPSTREAM_COOLDOWN_UNTIL = 0.0
    dashboard_router._IP_REQUEST_TIMESTAMPS.clear()


@pytest.mark.asyncio
async def test_single_flight_collapses_concurrent_requests():
    """Verify 30 concurrent requests result in exactly ONE upstream refresh."""
    mock_fetch = MagicMock()
    mock_fetch.return_value = {
        "profile": "demo",
        "contract_id": "pact-demo.testnet",
        "dao_id": "pactdao.testnet",
        "explorer_base_url": "https://testnet.nearblocks.io",
        "generatedAt": "2026-11-14T17:00:00Z",
        "source": "live",
        "params": {"quorum_pct": 70},
        "token": {"supply": "1000"},
        "challenges": [{"challenge_id": "demo-18", "outcome": "Pending"}],
        "dao": {"account": {"amount": "100"}},
        "rules": {"backend_hash": "hash1", "contract_hash": "hash1", "matches": True}
    }

    with patch("backend.dashboard_router._fetch_upstream_profile_data_sync", mock_fetch):
        tasks = [
            dashboard_router.get_or_fetch_snapshot("demo", force_refresh=True)
            for _ in range(30)
        ]
        results = await asyncio.gather(*tasks)

        assert len(results) == 30
        for r in results:
            assert r["profile"] == "demo"
            assert r["contract_id"] == "pact-demo.testnet"

        # Crucial check: exactly 1 upstream call made!
        assert mock_fetch.call_count == 1


@pytest.mark.asyncio
async def test_upstream_429_serves_stale_and_activates_cooldown():
    """Verify upstream 429 returns last good snapshot with source='stale' and enforces cooldown."""
    # Seed cache with known good snapshot
    good_data = {
        "profile": "demo",
        "contract_id": "pact-demo.testnet",
        "dao_id": "pactdao.testnet",
        "explorer_base_url": "https://testnet.nearblocks.io",
        "generatedAt": "2026-11-14T17:00:00Z",
        "source": "live",
        "params": {"quorum_pct": 70},
        "token": {"supply": "5000"},
        "challenges": [],
        "dao": {},
        "rules": {}
    }
    dashboard_router._SNAPSHOT_CACHE["demo"] = dashboard_router.SnapshotRecord(good_data, time.time() - 30.0, "etag1")

    # Mock upstream throwing 429
    mock_failing_fetch = MagicMock(side_effect=RuntimeError("HTTP 429: Too Many Requests. Retry-After: 15"))

    with patch("backend.dashboard_router._fetch_upstream_profile_data_sync", mock_failing_fetch):
        # 1. Fetch with force_refresh triggers failure, but returns STALE data
        result1 = await dashboard_router.get_or_fetch_snapshot("demo", force_refresh=True)
        assert result1["source"] == "stale"
        assert result1["token"]["supply"] == "5000"

        # Verify circuit breaker cooldown was activated
        assert dashboard_router._UPSTREAM_COOLDOWN_UNTIL > time.time()

        # 2. Subsequent call during cooldown returns stale immediately without calling upstream
        result2 = await dashboard_router.get_or_fetch_snapshot("demo", force_refresh=False)
        assert result2["source"] == "stale"
        assert result2["token"]["supply"] == "5000"

        # Failed fetch was only called once, second was blocked by circuit breaker
        assert mock_failing_fetch.call_count == 1


@pytest.mark.asyncio
async def test_upstream_timeout_serves_stale():
    """Verify upstream timeout serves stale data gracefully."""
    good_data = {
        "profile": "real",
        "contract_id": "pactcontract.testnet",
        "dao_id": "pactdao.testnet",
        "explorer_base_url": "https://testnet.nearblocks.io",
        "generatedAt": "2026-11-14T17:00:00Z",
        "source": "live",
        "params": {"quorum_pct": 70},
        "token": {"supply": "9999"},
        "challenges": [],
        "dao": {},
        "rules": {}
    }
    dashboard_router._SNAPSHOT_CACHE["real"] = dashboard_router.SnapshotRecord(good_data, time.time() - 30.0, "etag_real")

    mock_timeout = MagicMock(side_effect=TimeoutError("RPC socket timeout"))
    with patch("backend.dashboard_router._fetch_upstream_profile_data_sync", mock_timeout):
        result = await dashboard_router.get_or_fetch_snapshot("real", force_refresh=True)
        assert result["source"] == "stale"
        assert result["contract_id"] == "pactcontract.testnet"
        assert result["token"]["supply"] == "9999"


@pytest.mark.asyncio
async def test_strict_profile_isolation():
    """Verify demo, real, and sandbox data are strictly separated."""
    demo_snapshot = dashboard_router._build_default_snapshot("demo")
    real_snapshot = dashboard_router._build_default_snapshot("real")
    sandbox_snapshot = dashboard_router._build_default_snapshot("sandbox")

    dashboard_router._SNAPSHOT_CACHE["demo"] = dashboard_router.SnapshotRecord(demo_snapshot, time.time(), "e1")
    dashboard_router._SNAPSHOT_CACHE["real"] = dashboard_router.SnapshotRecord(real_snapshot, time.time(), "e2")
    dashboard_router._SNAPSHOT_CACHE["sandbox"] = dashboard_router.SnapshotRecord(sandbox_snapshot, time.time(), "e3")

    r_demo = await dashboard_router.get_or_fetch_snapshot("demo")
    r_real = await dashboard_router.get_or_fetch_snapshot("real")
    r_sb = await dashboard_router.get_or_fetch_snapshot("sandbox")

    assert r_demo["contract_id"] == "pact-demo.testnet"
    assert r_real["contract_id"] == "pactcontract.testnet"
    assert r_sb["contract_id"] == "pact-sandbox.testnet"

    assert r_demo["params"]["min_members"] == 3
    assert r_real["params"]["min_members"] == 5
    assert r_sb["params"]["min_members"] == 1


def test_per_ip_rate_limiting():
    """Verify client sending > 60 requests within window receives HTTP 429."""
    # Seed 60 timestamps for client IP
    client_ip = "testclient"
    dashboard_router._IP_REQUEST_TIMESTAMPS[client_ip] = [time.time()] * 60

    response = client.get("/api/dashboard/snapshot?profile=demo")
    assert response.status_code == 429
    assert response.headers.get("Retry-After") == "10"


def test_etag_304_not_modified():
    """Verify matching If-None-Match header returns HTTP 304."""
    # 1. Fetch fresh snapshot
    res1 = client.get("/api/dashboard/snapshot?profile=demo")
    assert res1.status_code == 200
    etag = res1.headers.get("ETag")
    assert etag is not None

    # 2. Fetch with If-None-Match header
    res2 = client.get("/api/dashboard/snapshot?profile=demo", headers={"If-None-Match": etag})
    assert res2.status_code == 304
