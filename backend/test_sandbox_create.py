"""
Regression and automated unit tests for sandbox challenge creation:
- first create
- repeat create (idempotency, returns 200 with existing challenge)
- create during grace (idempotency, returns 200 with existing challenge in IN_GRACE)
- create after end + grace (auto-settles or schedules safely after previous end)
- concurrent / repeated creates with idempotency key
"""
import pytest
from unittest.mock import patch, MagicMock
from fastapi.testclient import TestClient

from backend.main import app
from backend import auth_store

client = TestClient(app)


@pytest.fixture
def auth_header():
    # Insert test session in auth_store
    session = auth_store.create_session(
        account_id="judge.testnet",
        public_key="ed25519:2923ytyYmrHu758JU8fMsotSAWTVpVzj3VbpDdDWF43N",
    )
    token = session["token"]
    return {"Authorization": f"Bearer {token}", "X-Pact-Profile": "sandbox"}


def test_first_create_sandbox_challenge(auth_header):
    now = 1000000
    with patch("time.time", return_value=now), \
         patch("backend.sandbox_router.view", return_value=[]), \
         patch("backend.sandbox_router.near_cli", return_value="Log [pact-sandbox.testnet]: Transaction Id: E88xS4xnTxDuFakeTxHash"), \
         patch("backend.sandbox_router.extract_tx_hash", return_value="E88xS4xnTxDuFakeTxHash"):

        # Mock view get_challenge returning the newly created challenge
        def mock_view(contract, method, args):
            if method == "get_challenges":
                return []
            if method == "get_challenge":
                return {
                    "challenge_id": f"sb-{now}",
                    "steps_per_day": 10,
                    "days_required": 1,
                    "start_ns": str((now + 120) * 1_000_000_000),
                    "end_ns": str((now + 120 + 300) * 1_000_000_000),
                    "enrolled_count": 0,
                    "outcome": "Pending"
                }
            return None

        with patch("backend.sandbox_router.view", side_effect=mock_view):
            res = client.post(
                "/api/sandbox/create-challenge",
                json={"steps_per_day": 10, "duration_seconds": 300, "countdown_seconds": 120},
                headers=auth_header,
            )
            assert res.status_code == 200
            data = res.json()
            assert data["success"] is True
            assert data["challenge_id"] == f"sb-{now}"
            assert data["contract_id"] == "pact-sandbox.testnet"
            assert data["already_active"] is False


def test_repeat_create_sandbox_challenge_is_idempotent(auth_header):
    """When a challenge is active or enrolling, creating again returns 200 with existing challenge."""
    now = 1000050
    now_ns = now * 1_000_000_000
    existing = [{
        "challenge_id": "sb-active-123",
        "steps_per_day": 10,
        "days_required": 1,
        "start_ns": str(now_ns + 60_000_000_000),  # enrolling
        "end_ns": str(now_ns + 360_000_000_000),
        "enrolled_count": 1,
        "outcome": "Pending",
    }]

    with patch("time.time", return_value=now), \
         patch("backend.sandbox_router.view", return_value=existing):
        res = client.post(
            "/api/sandbox/create-challenge",
            json={"steps_per_day": 10, "duration_seconds": 300, "countdown_seconds": 120},
            headers=auth_header,
        )
        assert res.status_code == 200
        data = res.json()
        assert data["success"] is True
        assert data["challenge_id"] == "sb-active-123"
        assert data["already_active"] is True
        assert data["existing"] is True
        assert data["phase"] == "ENROLLING"


def test_create_during_grace_returns_existing(auth_header):
    """When challenge has ended but is within 180s grace window, returns 200 with phase IN_GRACE."""
    now = 1000400
    now_ns = now * 1_000_000_000
    existing = [{
        "challenge_id": "sb-ingrace-456",
        "steps_per_day": 10,
        "days_required": 1,
        "start_ns": str(now_ns - 310_000_000_000),
        "end_ns": str(now_ns - 10_000_000_000), # ended 10s ago, grace is 180s
        "enrolled_count": 1,
        "outcome": "Pending",
    }]

    with patch("time.time", return_value=now), \
         patch("backend.sandbox_router.view", return_value=existing):
        res = client.post(
            "/api/sandbox/create-challenge",
            json={"steps_per_day": 10, "duration_seconds": 300, "countdown_seconds": 120},
            headers=auth_header,
        )
        assert res.status_code == 200
        data = res.json()
        assert data["success"] is True
        assert data["challenge_id"] == "sb-ingrace-456"
        assert data["already_active"] is True
        assert data["phase"] == "IN_GRACE"


def test_create_with_idempotency_key(auth_header):
    """Using an idempotency key caches and immediately returns the identical response."""
    now = 1000000
    key = "unique-idemp-key-xyz"
    first_res = {
        "success": True,
        "challenge_id": "sb-cached-key",
        "contract_id": "pact-sandbox.testnet",
        "already_active": False,
        "phase": "ENROLLING"
    }
    from backend.sandbox_router import _RECENT_IDEMPOTENCY_RESPONSES
    _RECENT_IDEMPOTENCY_RESPONSES[key] = first_res

    res = client.post(
        "/api/sandbox/create-challenge",
        json={"steps_per_day": 10, "duration_seconds": 300, "countdown_seconds": 120, "idempotency_key": key},
        headers=auth_header,
    )
    assert res.status_code == 200
    assert res.json() == first_res
