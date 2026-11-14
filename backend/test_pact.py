"""Endpoint tests for the pact eligibility pipeline + community aggregates.

Run with pytest (plugin autoload disabled for the same reason as the other
suites - a globally installed plugin pulls in a broken scipy):

    cd backend
    PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest -q test_pact.py

Since NEAR sign-in landed, every ingest is performed by a signed-in member
(member_id = the NEAR account, taken from the session - never from the
request body).

Privacy contract under test (the "done" criterion):
- POST /api/pact/eligibility returns ONLY the ingesting member's own verdict;
  raw records are never echoed back.
- GET  /api/pact/community returns ONLY community aggregates. No member ids,
  per-member steps, or per-member flags appear anywhere in the response.

Fixtures live in ../pact/fixtures/ and are dated to the week of
2026-11-09..15. The endpoint evaluates against the real clock, so every
fixture is shifted to the last fully-completed week before posting: that
keeps all records in the past (no spurious future_records flags) and makes
the community numbers deterministic (elapsed == 7 for that week).
"""

from __future__ import annotations

import base64
import os
import sys
import tempfile
from datetime import timedelta
from pathlib import Path

import pytest

# Isolated databases before importing the app (see test_auth.py for the
# full rationale; all stores are set so standalone runs stay hermetic).
TEST_DIR = Path(tempfile.mkdtemp(prefix="health_sync_pact_tests_"))
os.environ["HEALTH_SYNC_DB"] = str(TEST_DIR / "health_sync_test.db")
os.environ["HEALTH_SYNC_PACT_DB"] = str(TEST_DIR / "pact_test.db")
os.environ["HEALTH_SYNC_AUTH_DB"] = str(TEST_DIR / "auth_test.db")
os.environ["HEALTH_SYNC_API_KEY"] = ""
os.environ["PACT_CONTRACT_ID"] = "pactcontract.testnet"
os.environ["PACT_ATTESTATION_SECRET_KEY"] = base64.b64encode(
    b"\x42" * 32
).decode("ascii")

BACKEND_DIR = Path(__file__).resolve().parent
REPO_ROOT = BACKEND_DIR.parent  # pact/ lives here
sys.path.insert(0, str(BACKEND_DIR))
sys.path.insert(0, str(REPO_ROOT))

import pact_store  # noqa: E402
import support  # noqa: E402

client = support.client
RULES = support.RULES
MONDAY = support.LAST_COMPLETED_WEEK_START

# Member ids are NEAR account ids now (the session's identity).
ALICE = "alice.testnet"
BOB = "bob.testnet"
MALLORY = "mallory.testnet"
SPOOFER = "spoofer.testnet"
TOKYO_TARO = "tokyo-taro.testnet"
CAROL = "carol.testnet"
DAVE = "dave.testnet"
ERIN = "erin.testnet"
FRANK = "frank.testnet"
GRACE = "grace.testnet"


@pytest.fixture(autouse=True)
def reset_pact_state():
    """Every test starts from empty stores and the last completed week."""
    import auth_store
    import storage

    pact_store.clear_pact_db()
    auth_store.clear_auth_db()
    storage.clear_uploads()
    yield


def ingest(session: dict, fixture_name: str, timezone: str | None = None,
           week_start=None):
    return support.ingest_pact(session, fixture_name, timezone, week_start)


def test_rules_endpoint_still_healthy():
    response = client.get("/api/pact/rules")
    assert response.status_code == 200
    assert response.json()["steps_per_day"] == RULES.steps_per_day


def test_eligibility_requires_signin():
    response = client.post(
        "/api/pact/eligibility",
        json={"records": support.shifted_records("normal_week")},
    )
    assert response.status_code == 401


def test_community_404_before_any_ingest():
    response = client.get("/api/pact/community")
    assert response.status_code == 404


def test_ingest_normal_week_and_read_community():
    with support.signed_in(ALICE) as session:
        response = ingest(session, "normal_week")
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["memberId"] == ALICE
        assert body["daysCompleted"] == 7
        assert body["passed"] is True
        assert body["weekStart"] == MONDAY.isoformat()
        assert body["flags"] == []

        # Own-verdict response exposes no raw records at all.
        assert set(body.keys()) == {
            "memberId", "challengeId", "weekStart", "daysCompleted", "passed", "flags"
        }
        assert "startTime" not in response.text
        assert "recordData" not in response.text
        assert "8432" not in response.text  # no step counts echoed back

        community = client.get("/api/pact/community")
        assert community.status_code == 200, community.text
        data = community.json()
        assert set(data.keys()) == {
            "challengeId", "weekStart", "weekEnd", "enrolled", "onTrackPct", "daysLeft"
        }
        assert data["enrolled"] == 1
        assert data["onTrackPct"] == 100  # 7 completed days is on pace at any elapsed
        assert data["weekStart"] == MONDAY.isoformat()
        assert data["weekEnd"] == (MONDAY + timedelta(days=6)).isoformat()


def test_ingest_cheater_fails_and_is_flagged():
    with support.signed_in(MALLORY) as session:
        response = ingest(session, "cheater")
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["memberId"] == MALLORY
        assert body["daysCompleted"] == 1
        assert body["passed"] is False
        codes = [flag["code"] for flag in body["flags"]]
        assert "flat_daily_totals" in codes
        assert "impossible_step_rate" in codes


def test_ingest_manual_spoof_never_counts_manual_steps():
    with support.signed_in(SPOOFER) as session:
        response = ingest(session, "manual_entry_spoof")
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["daysCompleted"] == 0
        assert body["passed"] is False
        codes = [flag["code"] for flag in body["flags"]]
        assert "manual_entries_dropped" in codes
        assert "impossible_step_rate" not in codes  # dropped before the rate check


def test_ingest_timezone_edge_member_buckets_locally():
    with support.signed_in(TOKYO_TARO) as session:
        response = ingest(session, "timezone_edge", timezone="Asia/Tokyo")
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["daysCompleted"] == 7
        assert body["passed"] is True


def test_community_aggregates_across_members_and_hides_everything_else():
    members = (ALICE, BOB, MALLORY, SPOOFER)
    fixtures = {
        ALICE: "normal_week",
        BOB: "partial_week",
        MALLORY: "cheater",
        SPOOFER: "manual_entry_spoof",
    }
    with support.accounts_signed_in(*members) as sessions:
        for member in members:
            assert ingest(sessions[member], fixtures[member]).status_code == 200

        response = client.get("/api/pact/community")
        assert response.status_code == 200, response.text
        data = response.json()
        assert data["enrolled"] == 4
        # The week is fully elapsed, so on-track is exactly the passers:
        # alice (7 days) and... only alice passed. bob=3, mallory=1, spoofer=0.
        assert data["onTrackPct"] == 25
        assert data["daysLeft"] == 0

        text = response.text
        for member_id in members:
            assert member_id not in text, "community endpoint leaked a member id"
        assert "8432" not in text and "9107" not in text  # no step counts


def test_member_timezone_is_locked_after_first_ingest():
    with support.signed_in(CAROL) as session:
        first = ingest(session, "normal_week", timezone="Asia/Tokyo")
        assert first.status_code == 200, first.text

        # Same zone again (or omitted) is fine.
        assert ingest(session, "normal_week", timezone="Asia/Tokyo").status_code == 200
        assert ingest(session, "normal_week").status_code == 200

        # A different zone is rejected: day boundaries must not silently move.
        changed = ingest(session, "normal_week", timezone="America/New_York")
        assert changed.status_code == 400
        assert "locked" in changed.json()["detail"]


def test_invalid_timezone_rejected_on_first_ingest():
    with support.signed_in(DAVE) as session:
        response = ingest(session, "normal_week", timezone="Mars/Olympus_Mons")
        assert response.status_code == 400


def test_week_start_must_match_rules_weekday():
    with support.signed_in(ERIN) as session:
        tuesday = MONDAY + timedelta(days=1)
        response = ingest(session, "normal_week", week_start=tuesday)
        assert response.status_code == 400
        assert "weekStart" in response.json()["detail"]


def test_reingest_is_idempotent():
    with support.signed_in(FRANK) as session:
        first = ingest(session, "normal_week")
        assert first.status_code == 200
        second = ingest(session, "normal_week")
        assert second.status_code == 200
        assert first.json()["daysCompleted"] == second.json()["daysCompleted"]

        community = client.get("/api/pact/community").json()
        assert community["enrolled"] == 1  # not 2: re-ingest must not duplicate


def test_daily_results_stored_are_derived_not_raw():
    with support.signed_in(GRACE) as session:
        assert ingest(session, "normal_week").status_code == 200
        challenge = pact_store.get_active_challenge()
        member = pact_store.get_member(GRACE)
        days = pact_store.community_days(challenge["challengeId"])
        assert days == {GRACE: 7}
        assert member["timezone"] == "UTC"


def test_two_members_cannot_ingest_as_each_other():
    """Identity comes from the session; the same week holds separate
    verdicts per account even with identical record payloads."""
    with support.accounts_signed_in(ALICE, BOB) as sessions:
        assert ingest(sessions[ALICE], "normal_week").status_code == 200
        assert ingest(sessions[BOB], "normal_week").status_code == 200
        days = pact_store.community_days(
            pact_store.get_active_challenge()["challengeId"]
        )
        assert days == {ALICE: 7, BOB: 7}


def test_spn_allowlist_matching():
    from pact.eligibility import is_trusted_origin

    # Passes: real SPN origin
    assert is_trusted_origin("com.android.healthconnect.phone.j3e9fbda4bd2d3ba196e22e69884b8b11") is True

    # Rejected: empty suffix
    assert is_trusted_origin("com.android.healthconnect.phone.") is False

    # Rejected: phonex
    assert is_trusted_origin("com.android.healthconnect.phonex.abc") is False

    # Rejected: prefix without dot/suffix
    assert is_trusted_origin("com.android.healthconnect") is False

    # Rejected: arbitrary unlisted origin
    assert is_trusted_origin("com.fake.healthapp") is False
    assert is_trusted_origin("") is False
    assert is_trusted_origin(None) is False


def test_spn_recording_method_filtering():
    from pact.eligibility import (
        run_pipeline,
        RECORDING_METHOD_AUTOMATICALLY_RECORDED,
        RECORDING_METHOD_MANUAL_ENTRY,
        RECORDING_METHOD_UNKNOWN
    )
    from datetime import date

    rules = RULES
    week_start = date(2026, 11, 9)  # Monday

    # Valid record with SPN and recordingMethod 2
    record_valid = {
        "recordType": "StepsRecord",
        "startTime": "2026-11-09T10:00:00Z",
        "endTime": "2026-11-09T10:30:00Z",
        "recordData": {"count": 8500, "unit": "count"},
        "metadata": {
            "id": "rec-1",
            "dataOrigin": "com.android.healthconnect.phone.j3e9fbda4bd2d3ba196e22e69884b8b11",
            "recordingMethod": RECORDING_METHOD_AUTOMATICALLY_RECORDED,
            "clientRecordVersion": -1
        }
    }

    res = run_pipeline([record_valid], rules, week_start)
    assert res.daily[0].steps == 8500
    assert res.daily[0].day_passed is True

    # Invalid: manual entry (3)
    record_manual = dict(record_valid)
    record_manual["metadata"] = dict(record_valid["metadata"])
    record_manual["metadata"]["recordingMethod"] = RECORDING_METHOD_MANUAL_ENTRY
    record_manual["metadata"]["id"] = "rec-manual"

    res_manual = run_pipeline([record_manual], rules, week_start)
    assert res_manual.daily[0].steps == 0
    assert "manual_entries_dropped" in [f.code for f in res_manual.evaluation.flags]

    # Invalid: unknown recording method (0)
    record_unknown = dict(record_valid)
    record_unknown["metadata"] = dict(record_valid["metadata"])
    record_unknown["metadata"]["recordingMethod"] = RECORDING_METHOD_UNKNOWN
    record_unknown["metadata"]["id"] = "rec-unknown"

    res_unknown = run_pipeline([record_unknown], rules, week_start)
    assert res_unknown.daily[0].steps == 0
    assert "unknown_recording_method_dropped" in [f.code for f in res_unknown.evaluation.flags]


def test_sync_endpoint_timezone_locking():
    with support.signed_in(ALICE) as session:
        # First sync with timezone
        payload1 = {
            "deviceId": "test-device-tz",
            "syncTimestamp": "2026-11-12T10:00:00Z",
            "timezone": "Europe/Warsaw",
            "records": []
        }
        res1 = client.post(
            "/api/health/sync",
            json=payload1,
            headers={"Authorization": f"Bearer {session['token']}"}
        )
        assert res1.status_code == 200

        # Subsequent sync with same timezone succeeds
        res2 = client.post(
            "/api/health/sync",
            json=payload1,
            headers={"Authorization": f"Bearer {session['token']}"}
        )
        assert res2.status_code == 200

        # Changing timezone during active challenge fails with 409
        payload_diff = dict(payload1, timezone="America/New_York")
        res3 = client.post(
            "/api/health/sync",
            json=payload_diff,
            headers={"Authorization": f"Bearer {session['token']}"}
        )
        assert res3.status_code == 409
        assert "locked" in res3.json()["detail"]


def test_endpoint_profile_resolution_and_allowlist():
    # Demo profile via header
    res_demo = client.get("/api/pact/rules", headers={"X-Pact-Profile": "demo"})
    assert res_demo.status_code == 200
    assert res_demo.headers["x-pact-contract-id"] == "pact-demo.testnet"
    assert res_demo.headers["x-pact-rules-hash"] == "4b6af96bc4c907f075e164c65b28c5acb24edf7696043dda916effe29eb8b725"
    body_demo = res_demo.json()
    assert body_demo["submission_grace_secs"] == 420

    # Real profile via query param
    res_real = client.get("/api/pact/rules?profile=real")
    assert res_real.status_code == 200
    assert res_real.headers["x-pact-contract-id"] == "pactcontract.testnet"
    assert res_real.headers["x-pact-rules-hash"] == "c8f1e60f408ea298ee9d2f7718f2c436cd40e762f0422e8147c78afd115f5d19"
    body_real = res_real.json()
    assert body_real["submission_grace_secs"] == 3600

    # Attestation key endpoint per profile
    res_key_demo = client.get("/api/pact/attestation-key", headers={"X-Pact-Profile": "demo"})
    assert res_key_demo.status_code == 200
    assert res_key_demo.json()["contractId"] == "pact-demo.testnet"

    res_key_real = client.get("/api/pact/attestation-key", headers={"X-Pact-Profile": "real"})
    assert res_key_real.status_code == 200
    assert res_key_real.json()["contractId"] == "pactcontract.testnet"

    # Reject non-allowlisted contract or profile with 400
    for invalid in ["unknown-profile", "pactcontract-fake.testnet", "evil.testnet"]:
        res_bad = client.get(f"/api/pact/rules?profile={invalid}")
        assert res_bad.status_code == 400
        assert "non-allowlisted" in res_bad.json()["detail"]

        res_bad_header = client.get("/api/pact/rules", headers={"X-Pact-Contract": invalid})
        assert res_bad_header.status_code == 400
        assert "non-allowlisted" in res_bad_header.json()["detail"]


def test_profile_storage_and_attestation_isolation():
    with support.signed_in(ALICE) as session:
        # Ingest on DEMO profile
        res_demo = support.ingest_pact(session, "normal_week", extra_headers={"X-Pact-Profile": "demo"})
        assert res_demo.status_code == 200
        assert res_demo.json()["passed"] is True

        # Ingest on REAL profile
        res_real = support.ingest_pact(session, "normal_week", extra_headers={"X-Pact-Profile": "real"})
        assert res_real.status_code == 200
        assert res_real.json()["passed"] is True

        # Both stored under their distinct contract_ids
        ch_demo = pact_store.get_challenge_by_week_start(MONDAY.isoformat(), contract_id="pact-demo.testnet")
        ch_real = pact_store.get_challenge_by_week_start(MONDAY.isoformat(), contract_id="pactcontract.testnet")
        assert ch_demo is not None
        assert ch_real is not None
        assert ch_demo["contractId"] == "pact-demo.testnet"
        assert ch_real["contractId"] == "pactcontract.testnet"
        assert ch_demo["challengeId"] != ch_real["challengeId"]

        result_demo = pact_store.get_weekly_result(ch_demo["challengeId"], ALICE)
        result_real = pact_store.get_weekly_result(ch_real["challengeId"], ALICE)
        assert result_demo is not None
        assert result_real is not None
        assert result_demo["passed"] == 1
        assert result_real["passed"] == 1

        # Request attestation for DEMO contract
        att_demo = client.get(
            f"/api/pact/attestation/{MONDAY.isoformat()}?profile=demo",
            headers={"Authorization": f"Bearer {session['token']}"},
        )
        assert att_demo.status_code == 200
        body_demo = att_demo.json()
        assert body_demo["contractId"] == "pact-demo.testnet"

        # Request attestation for REAL contract
        att_real = client.get(
            f"/api/pact/attestation/{MONDAY.isoformat()}?profile=real",
            headers={"Authorization": f"Bearer {session['token']}"},
        )
        assert att_real.status_code == 200
        body_real = att_real.json()
        assert body_real["contractId"] == "pactcontract.testnet"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
