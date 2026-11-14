"""
Tests for the Health Connect Sync Backend.

Run directly:
    cd backend
    python test_backend.py

Or with pytest:
    cd backend
    PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest -q test_backend.py

Since NEAR sign-in landed, POST /api/health/sync requires an authenticated
session (Authorization: Bearer <token>) — uploads must be attributable to
a NEAR account. These tests sign in through the same fake-chain helpers as
test_auth.py.
"""

from __future__ import annotations

import base64
import os
import sys
import tempfile
from pathlib import Path

# Use an isolated SQLite database before importing the application.
TEST_DIR = Path(tempfile.mkdtemp(prefix="health_sync_backend_tests_"))
os.environ["HEALTH_SYNC_DB"] = str(TEST_DIR / "health_sync_test.db")
os.environ["HEALTH_SYNC_PACT_DB"] = str(TEST_DIR / "pact_test.db")
os.environ["HEALTH_SYNC_AUTH_DB"] = str(TEST_DIR / "auth_test.db")
os.environ["HEALTH_SYNC_API_KEY"] = ""
os.environ["PACT_CONTRACT_ID"] = "opaque-pact.testnet"
os.environ["PACT_ATTESTATION_SECRET_KEY"] = base64.b64encode(
    b"\x42" * 32
).decode("ascii")

BACKEND_DIR = Path(__file__).resolve().parent
REPO_ROOT = BACKEND_DIR.parent
sys.path.insert(0, str(BACKEND_DIR))
sys.path.insert(0, str(REPO_ROOT))

import support  # noqa: E402

client = support.client

import main as main_module  # noqa: E402
import storage  # noqa: E402
from main import app  # noqa: E402  (pylint: app is the FastAPI instance)

TESTER_ACCOUNT = "tester.testnet"

SAMPLE_DEVICE_ID = "test-device-uuid-12345"
SAMPLE_PAYLOAD = {
    "deviceId": SAMPLE_DEVICE_ID,
    "syncTimestamp": "2026-11-09T23:00:00Z",
    "summary": {
        "StepsRecord": 1,
        "HeartRateRecord": 1,
        "SleepSessionRecord": 1,
        "ExerciseSessionRecord": 1,
        "WeightRecord": 1,
        "BloodPressureRecord": 1,
    },
    "records": [
        {
            "recordType": "StepsRecord",
            "startTime": "2026-11-09T10:00:00Z",
            "endTime": "2026-11-09T10:30:00Z",
            "recordData": {"count": 1250, "unit": "count"},
            "metadata": {
                "id": "steps-id-001",
                "dataOrigin": "com.google.android.apps.fitness",
            },
        },
        {
            "recordType": "HeartRateRecord",
            "startTime": "2026-11-09T11:00:00Z",
            "endTime": "2026-11-09T11:05:00Z",
            "recordData": {
                "samples": [
                    {"beatsPerMinute": 72, "time": "2026-11-09T11:01:00Z"},
                    {"beatsPerMinute": 75, "time": "2026-11-09T11:03:00Z"},
                ],
                "sampleCount": 2,
                "unit": "beats_per_minute",
            },
            "metadata": {"id": "hr-id-001"},
        },
        {
            "recordType": "SleepSessionRecord",
            "startTime": "2026-11-08T23:00:00Z",
            "endTime": "2026-11-09T07:00:00Z",
            "recordData": {
                "title": "Night Sleep",
                "durationMinutes": 480,
                "stages": [
                    {
                        "stage": 4,
                        "startTime": "2026-11-08T23:30:00Z",
                        "endTime": "2026-11-09T02:00:00Z",
                    }
                ],
                "unit": "stages_and_duration_minutes",
            },
            "metadata": {"id": "sleep-id-001"},
        },
        {
            "recordType": "ExerciseSessionRecord",
            "startTime": "2026-11-09T17:00:00Z",
            "endTime": "2026-11-09T17:45:00Z",
            "recordData": {
                "exerciseType": 56,
                "title": "Evening Run",
                "durationMinutes": 45,
                "unit": "duration_minutes",
            },
            "metadata": {"id": "exercise-id-001"},
        },
        {
            "recordType": "WeightRecord",
            "startTime": "2026-11-09T08:00:00Z",
            "endTime": "2026-11-09T08:00:00Z",
            "recordData": {"weightKg": 74.5, "unit": "kilograms"},
            "metadata": {"id": "weight-id-001"},
        },
        {
            "recordType": "BloodPressureRecord",
            "startTime": "2026-11-09T08:05:00Z",
            "endTime": "2026-11-09T08:05:00Z",
            "recordData": {
                "systolicMmHg": 118.0,
                "diastolicMmHg": 78.0,
                "bodyPosition": 1,
                "measurementLocation": 1,
                "unit": "mmHg",
            },
            "metadata": {"id": "bp-id-001"},
        },
    ],
}


def reset_database() -> None:
    storage.clear_uploads()


def post_sample_payload(session: dict, api_key: str | None = None) -> dict:
    """POST the sample payload as the signed-in member."""
    headers = dict(session["headers"])
    if api_key is not None:
        headers["X-API-Key"] = api_key
    response = client.post("/api/health/sync", json=SAMPLE_PAYLOAD, headers=headers)
    assert response.status_code == 200, (
        f"Expected 200, got {response.status_code}: {response.text}"
    )
    return response.json()


def test_root():
    response = client.get("/")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "online"
    assert data["syncEndpoint"] == "/api/health/sync"
    assert data["dashboard"] == "/dashboard"
    assert data["uploadsEndpoint"] == "/api/health/uploads"
    assert data["authNonceEndpoint"] == "/api/auth/nonce"
    assert data["authVerifyEndpoint"] == "/api/auth/verify"
    print("GET / passed!")


def test_health_status():
    response = client.get("/api/health/status")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    print("GET /api/health/status passed!")


def test_sync_requires_session():
    reset_database()
    response = client.post("/api/health/sync", json=SAMPLE_PAYLOAD)
    assert response.status_code == 401
    print("POST /api/health/sync rejects anonymous uploads!")


def test_sync_health_data():
    reset_database()
    with support.signed_in(TESTER_ACCOUNT) as session:
        body = post_sample_payload(session)

        assert body["status"] == "success"
        assert body["receivedRecordsCount"] == 6
        assert body["deviceId"] == SAMPLE_DEVICE_ID
        assert body["accountId"] == TESTER_ACCOUNT  # attributed to the member
        assert body["uploadId"]
        assert body["retrievalUrl"] == f"/api/health/uploads/{body['uploadId']}"
        assert body["summary"]["StepsRecord"] == 1
        assert body["summary"]["HeartRateRecord"] == 1
        assert body["summary"]["SleepSessionRecord"] == 1
        assert body["summary"]["ExerciseSessionRecord"] == 1
        assert body["summary"]["WeightRecord"] == 1
        assert body["summary"]["BloodPressureRecord"] == 1
        assert body["serverTimestamp"]
    print("POST /api/health/sync passed with all 6 record types!")


def test_retrieval_endpoints():
    reset_database()
    with support.signed_in(TESTER_ACCOUNT) as session:
        body = post_sample_payload(session)
        upload_id = body["uploadId"]
        headers = session["headers"]

        # List uploads (scoped to the signed-in member)
        response = client.get("/api/health/uploads", headers=headers)
        assert response.status_code == 200, response.text
        listing = response.json()
        assert listing["count"] == 1
        assert listing["uploads"][0]["uploadId"] == upload_id
        assert listing["uploads"][0]["recordCount"] == 6
        assert listing["uploads"][0]["accountId"] == TESTER_ACCOUNT
        assert "StepsRecord" in listing["uploads"][0]["summary"]

        # Filter uploads by record type
        response = client.get(
            "/api/health/uploads?recordType=WeightRecord", headers=headers
        )
        assert response.status_code == 200
        assert response.json()["count"] == 1
        response = client.get(
            "/api/health/uploads?recordType=UnknownRecord", headers=headers
        )
        assert response.status_code == 200
        assert response.json()["count"] == 0

        # Full upload detail
        response = client.get(f"/api/health/uploads/{upload_id}", headers=headers)
        assert response.status_code == 200, response.text
        detail = response.json()
        assert detail["uploadId"] == upload_id
        assert detail["payload"]["deviceId"] == SAMPLE_DEVICE_ID
        assert len(detail["payload"]["records"]) == 6

        # Flattened records
        response = client.get("/api/health/records", headers=headers)
        assert response.status_code == 200
        assert response.json()["count"] == 6

        response = client.get(
            "/api/health/records?recordType=StepsRecord", headers=headers
        )
        assert response.status_code == 200
        records = response.json()["records"]
        assert len(records) == 1
        assert records[0]["recordType"] == "StepsRecord"
        assert records[0]["uploadId"] == upload_id
        assert records[0]["recordData"]["count"] == 1250

        response = client.get(
            f"/api/health/records?deviceId={SAMPLE_DEVICE_ID}", headers=headers
        )
        assert response.status_code == 200
        assert response.json()["count"] == 6

        # Stats
        response = client.get("/api/health/stats", headers=headers)
        assert response.status_code == 200
        stats = response.json()
        assert stats["uploads"] == 1
        assert stats["records"] == 6
        assert stats["devices"] == 1
        assert stats["byRecordType"]["BloodPressureRecord"] == 1

        # Missing upload
        response = client.get("/api/health/uploads/does-not-exist", headers=headers)
        assert response.status_code == 404

        # Delete one upload
        response = client.delete(f"/api/health/uploads/{upload_id}", headers=headers)
        assert response.status_code == 200
        assert response.json()["deleted"] is True
        assert (
            client.get(f"/api/health/uploads/{upload_id}", headers=headers).status_code
            == 404
        )
        assert (
            client.delete(f"/api/health/uploads/{upload_id}", headers=headers).status_code
            == 404
        )

    print("Retrieval endpoints passed!")


def test_clear_all_uploads():
    reset_database()
    with support.signed_in(TESTER_ACCOUNT) as session:
        post_sample_payload(session)
        post_sample_payload(session)
        headers = session["headers"]

        response = client.delete("/api/health/uploads", headers=headers)
        assert response.status_code == 400, "Clearing without ?confirm=true must fail"

        response = client.delete("/api/health/uploads?confirm=true", headers=headers)
        assert response.status_code == 200
        assert response.json()["deletedCount"] == 2

        response = client.get("/api/health/uploads", headers=headers)
        assert response.status_code == 200
        assert response.json()["count"] == 0
    print("DELETE /api/health/uploads passed!")


def test_dashboard():
    response = client.get("/dashboard")
    assert response.status_code == 200
    assert "Health Connect Uploads" in response.text
    assert "/api/health/uploads" in response.text
    print("GET /dashboard passed!")


def test_optional_api_key_enforcement():
    reset_database()
    main_module.EXPECTED_API_KEY = "secret-key"
    try:
        # Without the API key: rejected with 401.
        response = client.get("/api/admin/health/uploads")
        assert response.status_code == 401

        response = client.get(
            "/api/admin/health/uploads", headers={"X-API-Key": "secret-key"}
        )
        assert response.status_code == 200

        # Sync now needs BOTH the API key (when configured) and a session.
        with support.signed_in(TESTER_ACCOUNT) as session:
            response = client.post("/api/health/sync", json=SAMPLE_PAYLOAD)
            assert response.status_code == 401  # no API key

            response = client.post(
                "/api/health/sync",
                headers={"X-API-Key": "secret-key"},  # no Bearer session
                json=SAMPLE_PAYLOAD,
            )
            assert response.status_code == 401

            response = client.post(
                "/api/health/sync",
                json=SAMPLE_PAYLOAD,  # session, but no API key
                headers=session["headers"],
            )
            assert response.status_code == 401

            response = client.post(
                "/api/health/sync",
                headers={**session["headers"], "X-API-Key": "secret-key"},
                json=SAMPLE_PAYLOAD,
            )
            assert response.status_code == 200
    finally:
        main_module.EXPECTED_API_KEY = ""
        reset_database()
    print("Optional X-API-Key enforcement passed!")


if __name__ == "__main__":
    test_root()
    test_health_status()
    test_sync_requires_session()
    test_sync_health_data()
    test_retrieval_endpoints()
    test_clear_all_uploads()
    test_dashboard()
    test_optional_api_key_enforcement()
    print("\nALL BACKEND TESTS PASSED SUCCESSFULLY!")
