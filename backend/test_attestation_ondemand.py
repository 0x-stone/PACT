import os
import tempfile
import uuid
import pytest
from datetime import datetime, timezone, timedelta
from pathlib import Path

os.environ["PACT_DEV_MODE"] = "1"

from fastapi.testclient import TestClient
from backend.main import app
import backend.storage as storage
import backend.pact_store as pact_store
import backend.auth_store as auth_store
import backend.contract_client as contract_client

client = TestClient(app)

def test_attestation_statuses(monkeypatch):
    account_id = f"user-{uuid.uuid4().hex[:8]}.testnet"
    session = auth_store.create_session(account_id, public_key="ed25519:testpubkey")
    auth_token = session["token"]
    headers = {
        "X-API-Key": "dev-key",
        "X-Pact-Profile": "DEMO",
        "Authorization": f"Bearer {auth_token}",
    }

    # 1. Unknown challenge -> 404
    monkeypatch.setattr(contract_client, "_rpc_view", lambda method, **kwargs: [])
    r_404 = client.get("/api/pact/attestation/unknown-ch", headers=headers)
    assert r_404.status_code == 404
    assert "unknown challenge" in r_404.json()["detail"]

    # Mock contract challenge for demo
    now = datetime.now(timezone.utc)
    mock_challenge_active = {
        "challenge_id": f"ch-active-{uuid.uuid4().hex[:6]}",
        "start_ns": int((now - timedelta(minutes=5)).timestamp() * 1e9),
        "end_ns": int((now + timedelta(minutes=5)).timestamp() * 1e9), # in progress!
        "steps_per_day": 50,
        "days_required": 1,
    }
    mock_challenge_ended = {
        "challenge_id": f"ch-ended-{uuid.uuid4().hex[:6]}",
        "start_ns": int((now - timedelta(minutes=20)).timestamp() * 1e9),
        "end_ns": int((now - timedelta(minutes=5)).timestamp() * 1e9), # ended 5m ago
        "steps_per_day": 50,
        "days_required": 1,
    }
    mock_challenge_expired = {
        "challenge_id": f"ch-expired-{uuid.uuid4().hex[:6]}",
        "start_ns": int((now - timedelta(days=5)).timestamp() * 1e9),
        "end_ns": int((now - timedelta(days=4)).timestamp() * 1e9), # expired past end + grace + margin
        "steps_per_day": 50,
        "days_required": 1,
    }

    def mock_rpc_view(method, **kwargs):
        if method == "get_challenges":
            return [mock_challenge_active, mock_challenge_ended, mock_challenge_expired]
        if method == "get_submission_grace_secs":
            return 420
        return None

    monkeypatch.setattr(contract_client, "_rpc_view", mock_rpc_view)

    # 2. Challenge not ended yet -> 409
    r_409 = client.get(f"/api/pact/attestation/{mock_challenge_active['challenge_id']}", headers=headers)
    assert r_409.status_code == 409
    assert "has not ended yet" in r_409.json()["detail"]

    # 3. Challenge expired -> 410
    r_410 = client.get(f"/api/pact/attestation/{mock_challenge_expired['challenge_id']}", headers=headers)
    assert r_410.status_code == 410
    assert "settlement window" in r_410.json()["detail"]

    # 4. Challenge ended, but no records synced -> 422
    r_422 = client.get(f"/api/pact/attestation/{mock_challenge_ended['challenge_id']}", headers=headers)
    assert r_422.status_code == 422
    assert "no health records found" in r_422.json()["detail"]

    # 5. Sync records with steps below target -> 200 with passed=False
    payload_fail = {
        "deviceId": "test-dev",
        "syncTimestamp": now.isoformat(),
        "timezone": "UTC",
        "summary": {"StepsRecord": 1},
        "records": [
            {
                "recordType": "StepsRecord",
                "startTime": (now - timedelta(minutes=15)).isoformat(),
                "endTime": (now - timedelta(minutes=14)).isoformat(),
                "recordData": {"count": 10, "unit": "count"},
                "metadata": {
                    "dataOrigin": "com.google.android.apps.fitness",
                    "recordingMethod": 2,
                },
            }
        ],
    }
    storage.save_upload(payload_fail, account_id=account_id)

    r_fail = client.get(f"/api/pact/attestation/{mock_challenge_ended['challenge_id']}", headers=headers)
    assert r_fail.status_code == 200
    fail_data = r_fail.json()
    assert fail_data["passed"] is False
    assert fail_data["challengeId"] == mock_challenge_ended["challenge_id"]
    assert fail_data["accountId"] == account_id
    assert fail_data["signature"]

    # 6. Add more steps to reach target -> 200 with passed=True
    mock_challenge_pass = {
        "challenge_id": f"ch-pass-{uuid.uuid4().hex[:6]}",
        "start_ns": int((now - timedelta(minutes=20)).timestamp() * 1e9),
        "end_ns": int((now - timedelta(minutes=5)).timestamp() * 1e9),
        "steps_per_day": 20,
        "days_required": 1,
    }
    def mock_rpc_view_2(method, **kwargs):
        if method == "get_challenges":
            return [mock_challenge_pass]
        if method == "get_submission_grace_secs":
            return 420
        return None
    monkeypatch.setattr(contract_client, "_rpc_view", mock_rpc_view_2)

    payload_pass = {
        "deviceId": "test-dev",
        "syncTimestamp": now.isoformat(),
        "timezone": "UTC",
        "summary": {"StepsRecord": 1},
        "records": [
            {
                "recordType": "StepsRecord",
                "startTime": (now - timedelta(minutes=12)).isoformat(),
                "endTime": (now - timedelta(minutes=10)).isoformat(),
                "recordData": {"count": 25, "unit": "count"},
                "metadata": {
                    "dataOrigin": "com.google.android.apps.fitness",
                    "recordingMethod": 2,
                },
            }
        ],
    }
    storage.save_upload(payload_pass, account_id=account_id)

    r_pass = client.get(f"/api/pact/attestation/{mock_challenge_pass['challenge_id']}", headers=headers)
    assert r_pass.status_code == 200
    pass_data = r_pass.json()
    assert pass_data["passed"] is True
    assert pass_data["challengeId"] == mock_challenge_pass["challenge_id"]
    assert pass_data["accountId"] == account_id
    assert pass_data["signature"]
