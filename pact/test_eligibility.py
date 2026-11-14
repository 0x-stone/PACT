"""Tests for the deterministic eligibility pipeline (pact/eligibility.py).

Two layers:

1. Fixture tests - the six scenario files in pact/fixtures/ (normal week,
   cheater, partial week, timezone edge, duplicate sources, manual-entry
   spoof). Each fixture carries its own expected verdict, flags and daily
   totals, so the JSON file doubles as the spec.
2. Unit tests - every filter, dedupe rule, sanity threshold, timezone
   resolution rule and community-aggregate formula, including the
   boundaries (exactly 8000 steps, exactly 300 steps/min, exactly 3 flat
   days).

Run (plugin autoload disabled because a globally installed pytest plugin
pulls in a scipy built against an incompatible numpy):

    PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python -m pytest pact -q
"""

from __future__ import annotations

import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest

from pact.eligibility import (
    ACCEPTED_RECORDING_METHODS,
    DEFAULT_TRUSTED_ORIGINS,
    MAX_STEPS_PER_DAY,
    MAX_STEPS_PER_MINUTE,
    RECORDING_METHOD_MANUAL_ENTRY,
    community_summary,
    evaluate_short_window,
    evaluate_week,
    is_short_window,
    resolve_boundary_timezone,
    run_pipeline,
    validate_timezone,
)
from pact.rules import load_rules

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"
RULES = load_rules()
WEEK_START = date(2026, 11, 9)  # a Monday; rules.week_start_day == 1
NOW = datetime.fromisoformat("2026-11-16T00:00:00+00:00")  # after the week

FIXTURE_NAMES = [
    "normal_week",
    "partial_week",
    "cheater",
    "timezone_edge",
    "duplicate_sources",
    "manual_entry_spoof",
]


def _dt(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _load_fixture(name: str) -> dict:
    return json.loads((FIXTURES_DIR / f"{name}.json").read_text(encoding="utf-8"))


def _codes(evaluation) -> list:
    return [flag.code for flag in evaluation.flags]


def _daily_map(result) -> dict:
    return {row.day_date.isoformat(): row.steps for row in result.daily}


def make_record(**overrides) -> dict:
    """One honest steps record in the app's exact wire schema."""
    record = {
        "recordType": "StepsRecord",
        "startTime": "2026-11-09T07:10:00Z",
        "endTime": "2026-11-09T08:25:00Z",
        "recordData": {"count": 8432, "unit": "count"},
        "metadata": {
            "id": "r-001",
            "clientRecordId": None,
            "clientRecordVersion": 0,
            "dataOrigin": "com.google.android.gms.fitness",
            "recordingMethod": 2,
            "lastModifiedTime": "2026-11-09T08:25:01Z",
        },
    }
    record.update(overrides)
    return record


def run(records, tz="UTC", week_start=WEEK_START, rules=RULES, now=NOW):
    return run_pipeline(records, rules, week_start, member_tz=tz, now=now)


# ---------------------------------------------------------------------------
# Fixture tests
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("name", FIXTURE_NAMES)
def test_fixture(name):
    fixture = _load_fixture(name)
    result = run_pipeline(
        fixture["records"],
        RULES,
        date.fromisoformat(fixture["weekStart"]),
        member_tz=fixture["timezone"],
        now=_dt(fixture["now"]),
    )
    expected = fixture["expect"]
    evaluation = result.evaluation

    assert evaluation.days_completed == expected["daysCompleted"], (
        f"{name}: days_completed {evaluation.days_completed} != {expected['daysCompleted']}"
    )
    assert evaluation.passed == expected["passed"]

    actual_codes = _codes(evaluation)
    for code in expected["flagCodes"]:
        assert code in actual_codes, f"{name}: expected flag {code}, got {actual_codes}"
    for code in expected.get("forbiddenFlagCodes", []):
        assert code not in actual_codes, f"{name}: forbidden flag {code} present"

    assert _daily_map(result) == expected["dailySteps"], f"{name}: daily totals differ"

    if "daysPassed" in expected:
        passed_days = {r.day_date.isoformat() for r in result.daily if r.day_passed}
        assert passed_days == set(expected["daysPassed"]), f"{name}: daysPassed differs"


def test_fixture_days_cover_full_week():
    result = run(_load_fixture("partial_week")["records"])
    assert [r.day_date.isoformat() for r in result.daily] == [
        (WEEK_START + timedelta(days=i)).isoformat() for i in range(7)
    ]
    assert result.daily[5].steps == 0 and result.daily[5].day_passed is False


def test_timezones_edge_fixture_is_zone_dependent():
    """The same records bucket differently under UTC: proves zone-aware days."""
    fixture = _load_fixture("timezone_edge")
    result = run(fixture["records"], tz="UTC")
    utc_map = _daily_map(result)
    assert utc_map["2026-11-09"] == 8432 + 8210  # 00:30 Tue Tokyo == Mon UTC
    assert utc_map["2026-11-11"] == 0
    assert utc_map != fixture["expect"]["dailySteps"]


def test_pipeline_is_deterministic():
    fixture = _load_fixture("cheater")
    first = run_pipeline(
        fixture["records"], RULES, WEEK_START, member_tz="UTC", now=NOW
    )
    second = run_pipeline(
        fixture["records"], RULES, WEEK_START, member_tz="UTC", now=NOW
    )
    assert first.model_dump_json() == second.model_dump_json()


# ---------------------------------------------------------------------------
# Timezone resolution
# ---------------------------------------------------------------------------
def test_policy_utc_uses_member_timezone():
    assert resolve_boundary_timezone(RULES, "Asia/Tokyo") == "Asia/Tokyo"
    assert resolve_boundary_timezone(RULES, "UTC") == "UTC"


def test_pinned_policy_zone_overrides_member_timezone():
    rules = RULES.model_copy(update={"day_boundary_timezone_policy": "America/New_York"})
    assert resolve_boundary_timezone(rules, "Asia/Tokyo") == "America/New_York"


def test_pinned_policy_zone_buckets_records_accordingly():
    """Tokyo member, pinned NY policy: the 00:30-Tokyo record lands on Monday."""
    rules = RULES.model_copy(update={"day_boundary_timezone_policy": "America/New_York"})
    fixture = _load_fixture("timezone_edge")
    result = run_pipeline(
        fixture["records"], rules, WEEK_START, member_tz="Asia/Tokyo", now=NOW
    )
    daily = _daily_map(result)
    assert daily["2026-11-09"] == 8432 + 8210  # 15:30Z is 10:30 Monday in New York


def test_validate_timezone_rejects_garbage():
    for bad in ["", "not-a-zone", "device-local", "utc+2"]:
        with pytest.raises(ValueError):
            validate_timezone(bad)
    assert validate_timezone("UTC") == "UTC"
    assert validate_timezone("Africa/Lagos") == "Africa/Lagos"


def test_week_start_must_match_rules():
    with pytest.raises(ValueError, match="week_start_day"):
        run([make_record()], week_start=date(2026, 11, 10))  # a Tuesday


# ---------------------------------------------------------------------------
# Stage 1: filters
# ---------------------------------------------------------------------------
def test_manual_entries_dropped():
    result = run([make_record(metadata=make_record()["metadata"] | {"recordingMethod": RECORDING_METHOD_MANUAL_ENTRY})])
    assert result.daily[0].steps == 0
    assert result.daily[0].day_passed is False
    assert "manual_entries_dropped" in _codes(result.evaluation)


def test_unknown_recording_method_dropped():
    meta = make_record()["metadata"] | {"recordingMethod": 0}
    result = run([make_record(metadata=meta)])
    assert result.daily[0].steps == 0
    assert "unknown_recording_method_dropped" in _codes(result.evaluation)


def test_accepted_methods_are_exactly_one_and_two():
    assert ACCEPTED_RECORDING_METHODS == {1, 2}


def test_untrusted_origin_dropped():
    meta = make_record()["metadata"] | {"dataOrigin": "com.evil.stepfarm"}
    result = run([make_record(metadata=meta)])
    assert result.daily[0].steps == 0
    assert "untrusted_origins_dropped" in _codes(result.evaluation)


def test_missing_metadata_dropped():
    result = run([make_record(metadata=None)])
    assert result.daily[0].steps == 0
    assert "untrusted_origins_dropped" in _codes(result.evaluation)


def test_missing_end_time_dropped():
    result = run([make_record(endTime=None)])
    assert result.daily[0].steps == 0
    assert "missing_end_time" in _codes(result.evaluation)


@pytest.mark.parametrize("bad_count", [-5, "8432", True, 8432.5, None])
def test_invalid_step_counts_dropped(bad_count):
    result = run([make_record(recordData={"count": bad_count, "unit": "count"})])
    assert result.daily[0].steps == 0
    assert "invalid_steps" in _codes(result.evaluation)


def test_invalid_time_range_dropped():
    result = run(
        [make_record(startTime="2026-11-09T08:25:00Z", endTime="2026-11-09T07:10:00Z")]
    )
    assert result.daily[0].steps == 0
    assert "invalid_time_range" in _codes(result.evaluation)


def test_future_records_dropped():
    # NOW is 2026-11-16T00:00Z; anything starting after it cannot be real yet.
    result = run([make_record(startTime="2026-11-16T01:00:00Z", endTime="2026-11-16T02:00:00Z")])
    assert "future_records" in _codes(result.evaluation)
    assert all(r.steps == 0 for r in result.daily)


def test_records_outside_week_ignored():
    result = run([make_record(startTime="2026-11-08T07:10:00Z", endTime="2026-11-08T08:25:00Z")])
    assert all(r.steps == 0 for r in result.daily)
    assert "outside_window" in _codes(result.evaluation)


def test_non_steps_records_flagged_not_counted():
    hr = make_record(
        recordType="HeartRateRecord",
        recordData={"samples": [], "sampleCount": 0, "unit": "beats_per_minute"},
    )
    result = run([hr])
    assert all(r.steps == 0 for r in result.daily)
    assert "non_steps_records" in _codes(result.evaluation)


def test_actively_recorded_steps_are_accepted():
    meta = make_record()["metadata"] | {"recordingMethod": 1}
    result = run([make_record(metadata=meta)])
    assert result.daily[0].steps == 8432


def test_default_trusted_origins_are_a_short_allowlist():
    assert isinstance(DEFAULT_TRUSTED_ORIGINS, frozenset)
    assert "com.opaque.healthsynctester" not in DEFAULT_TRUSTED_ORIGINS


# ---------------------------------------------------------------------------
# Stage 2: dedupe
# ---------------------------------------------------------------------------
def test_exact_duplicate_by_id_counts_once():
    record = make_record()
    result = run([record, make_record()])
    assert result.daily[0].steps == 8432
    assert "duplicate_records" in _codes(result.evaluation)


def test_same_content_without_id_is_fingerprint_deduped():
    meta = make_record()["metadata"] | {"id": None}
    base = make_record(metadata=meta)
    result = run([base, make_record(metadata=meta)])
    assert result.daily[0].steps == 8432
    assert "duplicate_records" in _codes(result.evaluation)


def test_different_records_same_day_both_count():
    morning = make_record(
        startTime="2026-11-09T07:10:00Z",
        endTime="2026-11-09T07:40:00Z",
        recordData={"count": 3000, "unit": "count"},
    )
    evening = make_record(
        startTime="2026-11-09T18:05:00Z",
        endTime="2026-11-09T18:45:00Z",
        recordData={"count": 5432, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": "r-evening"},
    )
    result = run([morning, evening])
    assert result.daily[0].steps == 8432
    assert "duplicate_records" not in _codes(result.evaluation)


# ---------------------------------------------------------------------------
# Stage 3: rate sanity + aggregation
# ---------------------------------------------------------------------------
def test_rate_exactly_at_limit_is_kept():
    # 3000 steps in 10 minutes == 300 steps/min exactly (not a violation).
    record = make_record(
        startTime="2026-11-09T07:00:00Z",
        endTime="2026-11-09T07:10:00Z",
        recordData={"count": 3000, "unit": "count"},
    )
    result = run([record])
    assert result.daily[0].steps == 3000
    assert "impossible_step_rate" not in _codes(result.evaluation)


def test_rate_one_step_over_limit_excluded():
    record = make_record(
        startTime="2026-11-09T07:00:00Z",
        endTime="2026-11-09T07:10:00Z",
        recordData={"count": 3001, "unit": "count"},
    )
    result = run([record])
    assert result.daily[0].steps == 0
    assert "impossible_step_rate" in _codes(result.evaluation)


def test_impossible_rate_record_does_not_poison_the_rest_of_the_day():
    honest = make_record()
    burst = make_record(
        startTime="2026-11-09T09:00:00Z",
        endTime="2026-11-09T09:20:00Z",
        recordData={"count": 25000, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": "r-burst"},
    )
    result = run([honest, burst])
    assert result.daily[0].steps == 8432  # only the honest record counts
    assert result.daily[0].day_passed is True


def test_max_per_origin_not_sum_across_origins():
    phone = make_record()
    watch = make_record(
        recordData={"count": 9000, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": "r-watch", "dataOrigin": "com.sec.android.app.shealth"},
    )
    result = run([phone, watch])
    assert result.daily[0].steps == 9000
    assert result.daily[0].origins == [
        "com.google.android.gms.fitness",
        "com.sec.android.app.shealth",
    ]
    assert "multiple_origins_same_day" in _codes(result.evaluation)


def test_same_origin_overlapping_intervals_summed_and_flagged():
    a = make_record(startTime="2026-11-09T07:00:00Z", endTime="2026-11-09T08:00:00Z",
                    recordData={"count": 4000, "unit": "count"})
    b = make_record(startTime="2026-11-09T07:30:00Z", endTime="2026-11-09T08:30:00Z",
                    recordData={"count": 4000, "unit": "count"},
                    metadata=make_record()["metadata"] | {"id": "r-b"})
    result = run([a, b])
    assert result.daily[0].steps == 8000
    assert "overlapping_same_origin" in _codes(result.evaluation)


# ---------------------------------------------------------------------------
# Stage 4: day-level sanity
# ---------------------------------------------------------------------------
def test_daily_total_over_cap_invalidates_day():
    record = make_record(
        startTime="2026-11-09T07:00:00Z",
        endTime="2026-11-09T15:00:00Z",  # 8h -> 218 steps/min, rate-legal
        recordData={"count": MAX_STEPS_PER_DAY + 1, "unit": "count"},
    )
    result = run([record])
    assert result.daily[0].steps == MAX_STEPS_PER_DAY + 1
    assert result.daily[0].day_passed is False
    assert "daily_total_over_cap" in _codes(result.evaluation)
    assert "daily_total_over_cap" in result.daily[0].flags


def test_flat_totals_three_days_invalidated():
    days = [make_record(
        startTime=f"2026-11-{day:02d}T07:00:00Z",
        endTime=f"2026-11-{day:02d}T08:30:00Z",
        recordData={"count": 8500, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": f"r-flat-{day}"},
    ) for day in (9, 10, 11)]
    result = run(days)
    assert all(r.steps == 8500 for r in result.daily[:3])
    assert all(r.day_passed is False for r in result.daily[:3])
    assert result.evaluation.days_completed == 0
    assert "flat_daily_totals" in _codes(result.evaluation)


def test_flat_totals_two_days_are_fine():
    days = [make_record(
        startTime=f"2026-11-{day:02d}T07:00:00Z",
        endTime=f"2026-11-{day:02d}T08:30:00Z",
        recordData={"count": 8500, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": f"r-ok-{day}"},
    ) for day in (9, 10)]
    result = run(days)
    assert result.evaluation.days_completed == 2
    assert "flat_daily_totals" not in _codes(result.evaluation)


def test_flat_totals_below_threshold_not_flagged():
    days = [make_record(
        startTime=f"2026-11-{day:02d}T07:00:00Z",
        endTime=f"2026-11-{day:02d}T08:00:00Z",
        recordData={"count": 7000, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": f"r-low-{day}"},
    ) for day in (9, 10, 11)]
    result = run(days)
    assert "flat_daily_totals" not in _codes(result.evaluation)
    assert result.evaluation.days_completed == 0


# ---------------------------------------------------------------------------
# Stage 5: thresholds
# ---------------------------------------------------------------------------
def test_exactly_steps_per_day_counts():
    record = make_record(recordData={"count": RULES.steps_per_day, "unit": "count"})
    assert run([record]).daily[0].day_passed is True


def test_one_step_below_threshold_does_not_count():
    record = make_record(recordData={"count": RULES.steps_per_day - 1, "unit": "count"})
    assert run([record]).daily[0].day_passed is False


def test_days_required_boundary():
    days = []
    for i in range(RULES.days_required):
        day = 9 + i
        days.append(make_record(
            startTime=f"2026-11-{day:02d}T07:00:00Z",
            endTime=f"2026-11-{day:02d}T08:30:00Z",
            recordData={"count": 8100 + i, "unit": "count"},
            metadata=make_record()["metadata"] | {"id": f"r-pass-{i}"},
        ))
    assert run(days).evaluation.passed is True
    assert run(days[:-1]).evaluation.passed is False  # days_required - 1 days


def test_evaluate_week_is_run_pipeline_verdict_only():
    fixture = _load_fixture("cheater")
    verdict = evaluate_week(
        fixture["records"], RULES, WEEK_START, member_tz="UTC", now=NOW
    )
    full = run_pipeline(fixture["records"], RULES, WEEK_START, member_tz="UTC", now=NOW)
    assert verdict == full.evaluation


# ---------------------------------------------------------------------------
# Community aggregates
# ---------------------------------------------------------------------------
def _at(day: str, hour: int = 12) -> datetime:
    return datetime.fromisoformat(f"{day}T{hour:02d}:00:00+00:00")


def test_community_pace_math():
    # Thursday (3 fully elapsed days), days_required=5: 2 days is behind,
    # 3 days is on pace (3*7 >= 5*3).
    summary = community_summary(RULES, WEEK_START, {"a": 2, "b": 3}, now=_at("2026-11-12"))
    assert summary.enrolled == 2
    assert summary.on_track_pct == 50
    assert summary.days_left == 4


def test_community_monday_everyone_on_track():
    summary = community_summary(RULES, WEEK_START, {"a": 0, "b": 0}, now=_at("2026-11-09"))
    assert summary.on_track_pct == 100
    assert summary.days_left == 7


def test_community_after_week_on_track_equals_passed():
    summary = community_summary(RULES, WEEK_START, {"a": 5, "b": 4}, now=_at("2026-11-20"))
    assert summary.on_track_pct == 50
    assert summary.days_left == 0


def test_community_empty_is_zero_percent():
    summary = community_summary(RULES, WEEK_START, {}, now=_at("2026-11-12"))
    assert summary.enrolled == 0
    assert summary.on_track_pct == 0


def test_community_pct_is_floored():
    summary = community_summary(RULES, WEEK_START, {"a": 3, "b": 0, "c": 0}, now=_at("2026-11-12"))
    assert summary.on_track_pct == 33  # 1/3 -> floor(33.33)


def test_community_reference_zone_follows_pinned_policy():
    rules = RULES.model_copy(update={"day_boundary_timezone_policy": "America/New_York"})
    # 02:00 UTC on Nov 16 is still Sunday Nov 15 in New York.
    summary = community_summary(rules, WEEK_START, {"a": 5}, now=_at("2026-11-16", hour=2))
    assert summary.days_left == 1
    # Under the default UTC policy the same instant is Monday Nov 16: week over.
    summary_utc = community_summary(RULES, WEEK_START, {"a": 5}, now=_at("2026-11-16", hour=2))
    assert summary_utc.days_left == 0


# ---------------------------------------------------------------------------
# Short-window tests (sub-24h demo / short-duration challenges)
# ---------------------------------------------------------------------------

def test_short_window_records_before_start_do_not_count():
    """Records whose START time is strictly before [start_ns, end_ns] do NOT count."""
    start_ns = 1_794_500_000_000_000_000
    end_ns = start_ns + 2 * 3600 * 1_000_000_000  # 2-hour window

    start_dt = datetime.fromtimestamp(start_ns / 1_000_000_000, tz=timezone.utc)
    end_dt = datetime.fromtimestamp(end_ns / 1_000_000_000, tz=timezone.utc)

    # 1. Record just 1 second BEFORE window start: MUST NOT COUNT
    rec_before = make_record(
        startTime=(start_dt - timedelta(seconds=1)).isoformat().replace("+00:00", "Z"),
        endTime=(start_dt + timedelta(minutes=10)).isoformat().replace("+00:00", "Z"),
        recordData={"count": 1000, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": "rec-before"},
    )

    # 2. Record exactly at window start: MUST COUNT
    rec_inside = make_record(
        startTime=start_dt.isoformat().replace("+00:00", "Z"),
        endTime=(start_dt + timedelta(minutes=15)).isoformat().replace("+00:00", "Z"),
        recordData={"count": 600, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": "rec-inside"},
    )

    # 3. Record after window end: MUST NOT COUNT
    rec_after = make_record(
        startTime=(end_dt + timedelta(seconds=10)).isoformat().replace("+00:00", "Z"),
        endTime=(end_dt + timedelta(minutes=20)).isoformat().replace("+00:00", "Z"),
        recordData={"count": 800, "unit": "count"},
        metadata=make_record()["metadata"] | {"id": "rec-after"},
    )

    res = evaluate_short_window(
        records=[rec_before, rec_inside, rec_after],
        start_ns=start_ns,
        end_ns=end_ns,
        steps_target=500,
    )

    assert res.outside_window == 2
    assert res.records_counted == 1
    assert res.total_steps == 600
    assert res.passed is True

    # If steps target is 700, 600 steps fails (rec_before's 1000 steps was excluded)
    res_fail = evaluate_short_window(
        records=[rec_before, rec_inside, rec_after],
        start_ns=start_ns,
        end_ns=end_ns,
        steps_target=700,
    )
    assert res_fail.total_steps == 600
    assert res_fail.passed is False


def test_short_window_rejects_greater_than_24h():
    """Short window must reject windows >= 24 hours."""
    start_ns = 1_794_500_000_000_000_000
    end_ns = start_ns + 25 * 3600 * 1_000_000_000
    with pytest.raises(ValueError, match="shorter than 24 hours"):
        evaluate_short_window([], start_ns, end_ns, 500)


# ---------------------------------------------------------------------------
# Consumer check: the backend imports this exact module
# ---------------------------------------------------------------------------
def test_backend_imports_the_same_pipeline():
    import backend.main as backend_main

    assert backend_main.evaluate_week is evaluate_week
