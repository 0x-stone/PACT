"""Deterministic eligibility pipeline: raw Health Connect records -> week verdict.

Pure functions only. No I/O, no randomness, no AI: the same inputs always
produce the same outputs, so every member, the simulation, and (later) any
auditor re-running the pipeline agree on who passed.

Input is one member's raw records exactly as the Android app serializes them
(``app/.../model/HealthModels.kt`` + ``HealthConnectManager.kt``):

    {"recordType": "StepsRecord",            # exact string the app emits
     "startTime": "2026-11-09T14:00:00Z",    # Instant.toString(), UTC
     "endTime":   "2026-11-09T15:00:00Z",
     "recordData": {"count": 8432, "unit": "count"},
     "metadata": {"id": "...", "clientRecordId": null, "clientRecordVersion": 0,
                  "dataOrigin": "com.google.android.gms.fitness",
                  "recordingMethod": 2,
                  "lastModifiedTime": "..."}}

Pipeline stages (in order):

1. Filter      - keep StepsRecord only; drop manual entries, unknown recording
                methods, and records whose dataOrigin is not in the trusted
                allowlist. Drop malformed records (no endTime, bad count,
                endTime <= startTime) and records from the future.
2. Dedupe      - exact duplicates (same Health Connect record id, or the same
                origin/times/count fingerprint) count once. Cross-origin
                double counting is prevented at aggregation: a day's steps
                are the MAX of the per-origin sums, never the sum.
3. Aggregate   - bucket the surviving records into local days of the member's
                fixed day-boundary timezone; a record counts on the local
                date of its startTime.
4. Sanity      - impossible step rates (per record) and implausible daily
                totals are excluded/invalidated; perfectly flat daily totals
                (the same count repeated on several days) are invalidated.
5. Evaluate    - a day passes at >= rules.steps_per_day steps; the week passes
                at >= rules.days_required passing days. Pact-level quorum is
                settlement logic (``PactRules.quorum_met``), not member logic.

Honest trust model (verified, not assumed):

- ``recordingMethod`` / ``dataOrigin`` exist in the app's pinned SDK
  (``androidx.health.connect:connect-client:1.1.0-alpha11`` — the app compiles
  against both). Constant values are from the AndroidX ``Metadata`` reference:
  0=UNKNOWN, 1=ACTIVELY_RECORDED, 2=AUTOMATICALLY_RECORDED, 3=MANUAL_ENTRY.
- ``dataOrigin`` is written by Health Connect itself when the inserting app
  stores the record; a client app cannot spoof another app's package name.
- ``recordingMethod`` is chosen by the *inserting* app, so it is a hint, not
  proof. The allowlist of origins is therefore the real gate: only packages we
  have decided to trust may contribute steps. MANUAL_ENTRY (3) and UNKNOWN (0)
  are dropped outright; 1 and 2 are accepted from trusted origins only.
- The backend still trusts the signer/key of whoever POSTs records (see
  README). This module's job is deterministic aggregation under that trust.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from typing import Any, Dict, List, Mapping, Optional, Sequence
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, field_validator

from pact.rules import PactRules

# ---------------------------------------------------------------------------
# Health Connect recording-method constants (androidx.health.connect 1.1.0
# line, values verified against the AndroidX Metadata reference docs).
# ---------------------------------------------------------------------------
RECORDING_METHOD_UNKNOWN = 0
RECORDING_METHOD_ACTIVELY_RECORDED = 1
RECORDING_METHOD_AUTOMATICALLY_RECORDED = 2
RECORDING_METHOD_MANUAL_ENTRY = 3

#: Only actively/automatically recorded steps count. Manual entries are the
#: obvious spoof vector; UNKNOWN means the source could not attest how the
#: data was captured, so it is dropped conservatively.
ACCEPTED_RECORDING_METHODS = frozenset(
    {RECORDING_METHOD_ACTIVELY_RECORDED, RECORDING_METHOD_AUTOMATICALLY_RECORDED}
)

#: Apps allowed to contribute steps. Health Connect sets dataOrigin to the
#: inserting app's package name, so this is an allowlist of sensor-owning
#: packages. Deliberately short and editable: add your wearable's package
#: here after deciding to trust it. Not a claim that these are the only
#: honest apps in the world — just the ones this pact has decided to trust.
DEFAULT_TRUSTED_ORIGINS = frozenset(
    {
        "com.google.android.gms.fitness",   # Google Play services (on-device sensors)
        "com.google.android.apps.fitness",   # Google Fit app
        "com.sec.android.app.shealth",      # Samsung Health (phones + watches)
        "com.xiaomi.hm.health",              # Xiaomi / Mi Fitness (wearables)
    }
)

#: Health Connect on-device step sensor prefix (Android 14+ / June 2026 SPN update).
#: Admits any origin starting with "com.android.healthconnect.phone." with a non-empty suffix.
HEALTH_CONNECT_PHONE_PREFIX = "com.android.healthconnect.phone."


def is_trusted_origin(
    origin: Optional[str],
    trusted_origins: Any = DEFAULT_TRUSTED_ORIGINS,
) -> bool:
    """True if the origin matches an exact trusted package or the valid SPN prefix."""
    if not origin:
        return False
    if origin in trusted_origins:
        return True
    if origin.startswith(HEALTH_CONNECT_PHONE_PREFIX) and len(origin) > len(HEALTH_CONNECT_PHONE_PREFIX):
        return True
    return False

# ---------------------------------------------------------------------------
# Sanity thresholds. Generous against real humans, tight against scripts.
# ---------------------------------------------------------------------------
#: 300 steps/minute is elite-sprint cadence (~1.5-2x the fastest sustained
#: running cadence). Real walking is ~100-130, running ~160-190. Any record
#: above this is not human locomotion; it is injected data.
MAX_STEPS_PER_MINUTE = 300

#: 100,000 steps in a day is ~75-80 km — beyond any plausible pact member's
#: day. A daily aggregate above this means something double-counted or a
#: script ran; the day is invalidated (not the whole week).
MAX_STEPS_PER_DAY = 100_000

#: The same step count on >= 3 separate days, to the step, is effectively
#: impossible for real sensor data (scripted injection). Those days are
#: invalidated. Only counted for days at/above steps_per_day: a repeated
#: below-threshold total cannot inflate the week anyway.
FLAT_DAILY_TOTAL_REPEATS = 3

#: The recordType string the app emits for steps (HealthConnectManager.kt).
STEPS_RECORD_TYPE = "StepsRecord"


def _flag(code: str, detail: str) -> "Flag":
    return Flag(code=code, detail=detail)


# ---------------------------------------------------------------------------
# Input models (mirror of the app's SyncRecord JSON, tolerant of extra keys
# so an app update does not break ingestion of the fields we rely on)
# ---------------------------------------------------------------------------
class RecordMetadata(BaseModel):
    """The subset of Health Connect ``Metadata`` the app serializes."""

    model_config = ConfigDict(extra="ignore")

    id: Optional[str] = None
    clientRecordId: Optional[str] = None
    clientRecordVersion: Optional[int] = None
    dataOrigin: Optional[str] = None
    recordingMethod: Optional[int] = None
    lastModifiedTime: Optional[str] = None


class HealthRecord(BaseModel):
    """One record exactly as the Android app puts it on the wire."""

    model_config = ConfigDict(extra="ignore")

    recordType: str
    startTime: datetime
    endTime: Optional[datetime] = None
    recordData: Dict[str, Any]
    metadata: Optional[RecordMetadata] = None

    @field_validator("startTime")
    @classmethod
    def _start_must_be_tz_aware(cls, value: datetime) -> datetime:
        """``Instant.toString()`` is always UTC-offset-qualified; naive means malformed."""
        if value.tzinfo is None:
            raise ValueError("startTime must be a UTC-offset ISO-8601 timestamp")
        return value

    @property
    def steps(self) -> Optional[int]:
        """Step count if this is a well-formed steps record, else None.

        The app sends ``record.count`` (a Kotlin Long -> JSON int). Reject
        bools, floats, strings and negatives loudly by returning None; the
        caller drops the record and flags it.
        """
        if self.recordType != STEPS_RECORD_TYPE:
            return None
        raw = self.recordData.get("count")
        if isinstance(raw, bool) or not isinstance(raw, int) or raw < 0:
            return None
        return raw


# ---------------------------------------------------------------------------
# Output models
# ---------------------------------------------------------------------------
class Flag(BaseModel):
    """A pipeline finding. Codes are stable; details are deterministic."""

    code: str
    detail: str


class DailyResult(BaseModel):
    """One member's day after filtering, dedupe and sanity checks."""

    day_date: date
    steps: int
    day_passed: bool
    flags: List[str] = []
    origins: List[str] = []


class WeekEvaluation(BaseModel):
    """What ``evaluate_week`` returns for one member."""

    days_completed: int
    passed: bool
    flags: List[Flag] = []


class PipelineResult(BaseModel):
    """Full pipeline output: per-day rows plus the week verdict."""

    daily: List[DailyResult]
    evaluation: WeekEvaluation


class CommunitySummary(BaseModel):
    """Community-level aggregates only. No per-member data, ever."""

    enrolled: int
    on_track_pct: int
    days_left: int


# ---------------------------------------------------------------------------
# Timezone resolution
# ---------------------------------------------------------------------------
def validate_timezone(name: str) -> str:
    """Validate a fixed IANA zone name (or 'UTC'); raise ValueError otherwise."""
    if name == "UTC":
        return name
    try:
        ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError, KeyError, TypeError) as exc:
        raise ValueError(
            f"member timezone must be 'UTC' or a valid IANA zone name, got {name!r}"
        ) from exc
    return name


def resolve_boundary_timezone(rules: PactRules, member_tz: str) -> str:
    """Which fixed zone this member's days are bucketed in.

    The pact policy is authoritative:

    - policy names a specific IANA zone -> every member is bucketed in that
      zone (a pact that wants everyone on one clock, e.g. "Africa/Lagos").
    - policy "UTC" -> the pact does not pin a zone; each member's *fixed,
      declared* IANA timezone (``members.timezone``, default "UTC") defines
      their day boundary.

    Determinism holds in both branches: the zone is a stored string chosen
    from the IANA database, never the device's drifting local clock. If you
    want strict pact-wide UTC days for everyone, set the policy to a pinned
    zone and ignore member timezones — the code path is identical.
    """
    validate_timezone(member_tz)
    policy = rules.day_boundary_timezone_policy
    if policy == "UTC":
        return member_tz
    return policy  # already validated by PactRules to be a real IANA zone


def current_week_start(now: datetime, rules: PactRules, tz: str) -> date:
    """The most recent week-start date (per rules.week_start_day) on/before now."""
    zone = ZoneInfo(validate_timezone(tz))
    today = now.astimezone(zone).date()
    offset = (today.isoweekday() - rules.week_start_day) % 7  # ISO: 1=Mon..7=Sun
    return today - timedelta(days=offset)


# ---------------------------------------------------------------------------
# Stage helpers (each pure and independently testable)
# ---------------------------------------------------------------------------
class _Dropped(Exception):
    """Internal control flow: record dropped with a flag code + detail."""


def _rate_violation(steps: int, start: datetime, end: datetime) -> bool:
    """steps/minute above MAX_STEPS_PER_MINUTE (or a non-positive duration)."""
    seconds = (end - start).total_seconds()
    if seconds <= 0:
        return steps > 0  # zero-duration with steps is impossible by definition
    return steps * 60 > MAX_STEPS_PER_MINUTE * seconds


def _dedupe_key(record: HealthRecord) -> tuple:
    """Health Connect id if present; else an exact-content fingerprint."""
    meta = record.metadata
    if meta and meta.id:
        return ("id", meta.id)
    return (
        "fingerprint",
        record.recordType,
        meta.dataOrigin if meta else None,
        record.startTime,
        record.endTime,
        record.steps,
    )


def _local_date(start: datetime, zone: ZoneInfo) -> date:
    """Local calendar date of a record's start, in the day-boundary zone."""
    return start.astimezone(zone).date()


# ---------------------------------------------------------------------------
# The pipeline
# ---------------------------------------------------------------------------
def run_pipeline(
    records: Sequence[Dict[str, Any]],
    rules: PactRules,
    week_start: date,
    member_tz: str = "UTC",
    trusted_origins: Sequence[str] = DEFAULT_TRUSTED_ORIGINS,
    now: Optional[datetime] = None,
) -> PipelineResult:
    """Evaluate one member's week. Pure; see module docstring for stages.

    Args:
        records:      raw SyncRecord dicts (the app's schema).
        rules:        validated pact rules (single source of truth).
        week_start:   the week's first day; must fall on rules.week_start_day.
        member_tz:    the member's fixed declared IANA zone (default "UTC").
        trusted_origins: dataOrigin allowlist (see DEFAULT_TRUSTED_ORIGINS).
        now:          reference instant for the future-record check; None
                      disables that check (used by offline fixtures).

    Returns:
        PipelineResult with all 7 daily rows (steps=0 for empty days) and the
        week verdict. Flags are only emitted when something was actually
        dropped/invalidated — silent zeros are not reported.
    """
    if week_start.isoweekday() != rules.week_start_day:
        raise ValueError(
            f"week_start {week_start} is a {week_start.isoweekday()} (ISO weekday), "
            f"but rules.week_start_day is {rules.week_start_day}"
        )
    zone = ZoneInfo(resolve_boundary_timezone(rules, member_tz))
    week_dates = [week_start + timedelta(days=i) for i in range(7)]
    week_days = {d.isoformat() for d in week_dates}
    origins_allowed = frozenset(trusted_origins)

    drop_counts: Dict[str, int] = {}
    kept: List[HealthRecord] = []
    outside_window = 0
    non_steps = 0

    def drop(code: str, record: HealthRecord) -> None:
        drop_counts[code] = drop_counts.get(code, 0) + 1

    for raw in records:
        record = raw if isinstance(raw, HealthRecord) else HealthRecord.model_validate(raw)

        # --- Stage 1: filter ------------------------------------------------
        if record.recordType != STEPS_RECORD_TYPE:
            non_steps += 1
            continue

        if now is not None and record.startTime > now:
            drop("future_records", record)
            continue

        local_day = _local_date(record.startTime, zone).isoformat()
        if local_day not in week_days:
            outside_window += 1
            continue

        meta = record.metadata
        if meta is None or meta.dataOrigin is None:
            drop("untrusted_origins_dropped", record)
            continue
        if not is_trusted_origin(meta.dataOrigin, origins_allowed):
            drop("untrusted_origins_dropped", record)
            continue
        if meta.recordingMethod == RECORDING_METHOD_MANUAL_ENTRY:
            drop("manual_entries_dropped", record)
            continue
        if meta.recordingMethod not in ACCEPTED_RECORDING_METHODS:
            drop("unknown_recording_method_dropped", record)
            continue
        if record.endTime is None:
            drop("missing_end_time", record)
            continue
        if record.steps is None:
            drop("invalid_steps", record)
            continue
        if record.endTime <= record.startTime:
            drop("invalid_time_range", record)
            continue

        kept.append(record)

    # --- Stage 2: dedupe exact duplicates ---------------------------------
    seen: set = set()
    unique: List[HealthRecord] = []
    duplicates = 0
    for record in kept:
        key = _dedupe_key(record)
        if key in seen:
            duplicates += 1
            continue
        seen.add(key)
        unique.append(record)

    # --- Stage 3: per-record sanity (impossible rates), then aggregate ----
    rate_violations = 0
    usable: List[HealthRecord] = []
    for record in unique:
        assert record.steps is not None and record.endTime is not None  # filtered above
        if _rate_violation(record.steps, record.startTime, record.endTime):
            rate_violations += 1
            continue
        usable.append(record)

    # Same-origin overlapping intervals are suspicious but not proof of
    # anything; they are summed within their origin (never across origins)
    # and reported, not silently merged.
    overlapping = 0
    by_origin: Dict[str, List[HealthRecord]] = {}
    for record in usable:
        by_origin.setdefault(record.metadata.dataOrigin, []).append(record)
    for origin_records in by_origin.values():
        for i in range(len(origin_records)):
            for j in range(i + 1, len(origin_records)):
                a, b = origin_records[i], origin_records[j]
                if a.startTime < b.endTime and b.startTime < a.endTime:  # type: ignore[operator]
                    overlapping += 1

    # Per-day, per-origin sums; the day's steps are the max across origins
    # (one body only moved once — summing phone+watch double counts it).
    per_day_origin: Dict[str, Dict[str, int]] = {}
    for record in usable:
        day = _local_date(record.startTime, zone).isoformat()
        origin = record.metadata.dataOrigin  # type: ignore[union-attr]
        per_day_origin.setdefault(day, {})
        per_day_origin[day][origin] = per_day_origin[day].get(origin, 0) + record.steps

    day_steps: Dict[str, int] = {}
    day_origins: Dict[str, List[str]] = {}
    multi_origin_days = 0
    for day, origin_sums in per_day_origin.items():
        day_steps[day] = max(origin_sums.values())
        day_origins[day] = sorted(origin_sums.keys())
        if len(origin_sums) > 1:
            multi_origin_days += 1

    # --- Stage 4: day-level sanity ------------------------------------------
    day_flags: Dict[str, List[str]] = {d: [] for d in week_days}
    over_cap_days: List[str] = []
    for day, total in sorted(day_steps.items()):
        if total > MAX_STEPS_PER_DAY:
            over_cap_days.append(day)
            day_flags[day].append("daily_total_over_cap")

    # Perfectly flat totals: the same count repeated across >= 3 counting days.
    counting_totals: Dict[str, List[str]] = {}
    for day, total in sorted(day_steps.items()):
        if total >= rules.steps_per_day:
            counting_totals.setdefault(total, []).append(day)
    flat_groups = [
        (total, days)
        for total, days in sorted(counting_totals.items())
        if len(days) >= FLAT_DAILY_TOTAL_REPEATS
    ]
    flat_days = [day for _, days in flat_groups for day in days]
    for day in flat_days:
        day_flags[day].append("flat_daily_totals")

    # --- Stage 5: evaluate ---------------------------------------------------
    daily: List[DailyResult] = []
    days_completed = 0
    for day in week_dates:
        iso = day.isoformat()
        total = day_steps.get(iso, 0)
        passed_day = total >= rules.steps_per_day and iso not in over_cap_days and iso not in flat_days
        if passed_day:
            days_completed += 1
        daily.append(
            DailyResult(
                day_date=day,
                steps=total,
                day_passed=passed_day,
                flags=[f for f in day_flags[iso] if f],
                origins=day_origins.get(iso, []),
            )
        )

    flags: List[Flag] = []
    if non_steps:
        flags.append(_flag("non_steps_records", f"ignored {non_steps} non-StepsRecord record(s)"))
    if outside_window:
        flags.append(
            _flag("outside_window", f"ignored {outside_window} record(s) outside the challenge week")
        )
    if future_count := drop_counts.get("future_records"):
        flags.append(_flag("future_records", f"dropped {future_count} record(s) dated in the future"))
    if untrusted := drop_counts.get("untrusted_origins_dropped"):
        flags.append(_flag("untrusted_origins_dropped", f"dropped {untrusted} record(s) from untrusted/unknown dataOrigin"))
    if manual := drop_counts.get("manual_entries_dropped"):
        flags.append(_flag("manual_entries_dropped", f"dropped {manual} manually-entered record(s)"))
    if unknown := drop_counts.get("unknown_recording_method_dropped"):
        flags.append(_flag("unknown_recording_method_dropped", f"dropped {unknown} record(s) with unknown recordingMethod"))
    if no_end := drop_counts.get("missing_end_time"):
        flags.append(_flag("missing_end_time", f"dropped {no_end} record(s) without endTime"))
    if bad_steps := drop_counts.get("invalid_steps"):
        flags.append(_flag("invalid_steps", f"dropped {bad_steps} record(s) with missing/negative/non-integer count"))
    if bad_range := drop_counts.get("invalid_time_range"):
        flags.append(_flag("invalid_time_range", f"dropped {bad_range} record(s) with endTime <= startTime"))
    if duplicates:
        flags.append(_flag("duplicate_records", f"counted {duplicates} exact duplicate record(s) once"))
    if overlapping:
        flags.append(_flag("overlapping_same_origin", f"{overlapping} overlapping interval pair(s) within one origin"))
    if multi_origin_days:
        flags.append(_flag("multiple_origins_same_day", f"{multi_origin_days} day(s) had multiple trusted origins; used the highest per-origin total"))
    if rate_violations:
        flags.append(
            _flag("impossible_step_rate", f"excluded {rate_violations} record(s) above {MAX_STEPS_PER_MINUTE} steps/min")
        )
    if over_cap_days:
        flags.append(
            _flag("daily_total_over_cap", f"invalidated day(s) {over_cap_days} above {MAX_STEPS_PER_DAY} steps")
        )
    if flat_groups:
        described = ", ".join(
            f"{total} on {days}" for total, days in flat_groups
        )
        flags.append(
            _flag("flat_daily_totals", f"invalidated day(s) with identical totals: {described}")
        )

    evaluation = WeekEvaluation(
        days_completed=days_completed,
        passed=days_completed >= rules.days_required,
        flags=flags,
    )
    return PipelineResult(daily=daily, evaluation=evaluation)


def evaluate_week(
    records: Sequence[Dict[str, Any]],
    rules: PactRules,
    week_start: date,
    member_tz: str = "UTC",
    trusted_origins: Sequence[str] = DEFAULT_TRUSTED_ORIGINS,
    now: Optional[datetime] = None,
) -> WeekEvaluation:
    """``evaluateWeek(records, rules)`` -> {daysCompleted, passed, flags[]}.

    Thin wrapper over ``run_pipeline`` for callers that only need the verdict.
    """
    return run_pipeline(
        records, rules, week_start, member_tz, trusted_origins, now
    ).evaluation


# ---------------------------------------------------------------------------
# Community aggregates (no per-member data leaves this function's inputs)
# ---------------------------------------------------------------------------
def community_summary(
    rules: PactRules,
    week_start: date,
    days_completed_by_member: Mapping[str, int],
    now: datetime,
) -> CommunitySummary:
    """enrolled, on-track % and days left for the whole pact.

    "On track" uses integer pace math mirroring ``quorum_met``:
    a member is on track when ``days_completed * 7 >= days_required * elapsed``,
    where ``elapsed`` is the number of fully-elapsed days of the week.
    "days left" counts the days of the week not yet fully elapsed (today
    included). After the week ends, on-track is exactly "passed".

    The reference clock is UTC unless the pact policy pins a zone, so these
    community numbers are identical for every caller.
    """
    if week_start.isoweekday() != rules.week_start_day:
        raise ValueError(
            f"week_start {week_start} does not match rules.week_start_day {rules.week_start_day}"
        )
    ref_zone = ZoneInfo(
        rules.day_boundary_timezone_policy
        if rules.day_boundary_timezone_policy != "UTC"
        else "UTC"
    )
    today = now.astimezone(ref_zone).date()
    elapsed = min(max((today - week_start).days, 0), 7)
    days_left = 7 - elapsed

    enrolled = len(days_completed_by_member)
    on_track = sum(
        1
        for days in days_completed_by_member.values()
        if days * 7 >= rules.days_required * elapsed
    )
    on_track_pct = (on_track * 100 // enrolled) if enrolled > 0 else 0
    return CommunitySummary(
        enrolled=enrolled,
        on_track_pct=on_track_pct,
        days_left=days_left,
    )


# ---------------------------------------------------------------------------
# Short-window eligibility (sub-24h demo / short-duration challenges)
# ---------------------------------------------------------------------------

class ShortWindowResult(BaseModel):
    """Evaluation result for a short-window (<24h) challenge."""
    model_config = ConfigDict(frozen=True)

    passed: bool
    total_steps: int
    steps_target: int
    origins: List[str]
    records_counted: int
    outside_window: int
    dropped_flags: Dict[str, int]


def is_short_window(start_ns: int, end_ns: int) -> bool:
    """True if challenge duration is strictly less than 24 hours (<86,400s)."""
    return (end_ns - start_ns) < 86_400 * 1_000_000_000


def evaluate_short_window(
    records: Sequence[Dict[str, Any] | HealthRecord],
    start_ns: int,
    end_ns: int,
    steps_target: int,
    trusted_origins: Sequence[str] = DEFAULT_TRUSTED_ORIGINS,
    now: Optional[datetime] = None,
) -> ShortWindowResult:
    """Evaluate qualification for a sub-24h (demo / short-duration) challenge.

    SHORT-WINDOW ELIGIBILITY RULE:
    1. A record counts if its START time falls inside [start_ns, end_ns] (no clipping).
    2. Applies only to challenges shorter than 24h; weekly behaviour and its tests
       remain completely unchanged.
    3. Records with startTime < start_ns or startTime > end_ns do NOT count.
       (Records strictly before the window start are counted under outside_window).
    4. Sensor filtering: drops manual entries, unknown recording methods, non-steps,
       and untrusted data origins using the same allowlist as the main pipeline.
    5. Cross-origin aggregation: steps are summed per sensor origin, and the
       window total is the MAX across eligible origins (never the sum of phone + watch).
    """
    if not is_short_window(start_ns, end_ns):
        raise ValueError("evaluate_short_window only applies to challenges shorter than 24 hours")

    start_dt = datetime.fromtimestamp(start_ns / 1_000_000_000, tz=timezone.utc)
    end_dt = datetime.fromtimestamp(end_ns / 1_000_000_000, tz=timezone.utc)
    origins_allowed = frozenset(trusted_origins)

    drop_counts: Dict[str, int] = {}
    kept: List[HealthRecord] = []
    outside_window = 0

    def drop(code: str) -> None:
        drop_counts[code] = drop_counts.get(code, 0) + 1

    for raw in records:
        record = raw if isinstance(raw, HealthRecord) else HealthRecord.model_validate(raw)

        if record.recordType != STEPS_RECORD_TYPE:
            continue

        if now is not None and record.startTime > now:
            drop("future_records")
            continue

        # Window check: START time must fall inside [start_ns, end_ns] (no clipping).
        # Records just before start_dt or after end_dt do NOT count.
        if record.startTime < start_dt or record.startTime > end_dt:
            outside_window += 1
            continue

        meta = record.metadata
        if meta is None or meta.dataOrigin is None or not is_trusted_origin(meta.dataOrigin, origins_allowed):
            drop("untrusted_origins_dropped")
            continue
        if meta.recordingMethod == RECORDING_METHOD_MANUAL_ENTRY:
            drop("manual_entries_dropped")
            continue
        if meta.recordingMethod not in ACCEPTED_RECORDING_METHODS:
            drop("unknown_recording_method_dropped")
            continue
        if record.endTime is None or record.steps is None or record.endTime <= record.startTime:
            drop("invalid_record")
            continue

        kept.append(record)

    # Dedupe exact duplicates
    seen = set()
    unique: List[HealthRecord] = []
    for record in kept:
        key = _dedupe_key(record)
        if key not in seen:
            seen.add(key)
            unique.append(record)

    # Per-record sanity (rate violation)
    usable: List[HealthRecord] = []
    for record in unique:
        assert record.steps is not None and record.endTime is not None
        if not _rate_violation(record.steps, record.startTime, record.endTime):
            usable.append(record)
        else:
            drop("rate_violation")

    # Sum per origin and take MAX
    per_origin: Dict[str, int] = {}
    for record in usable:
        assert record.metadata is not None and record.metadata.dataOrigin is not None
        origin = record.metadata.dataOrigin
        per_origin[origin] = per_origin.get(origin, 0) + (record.steps or 0)

    total_steps = max(per_origin.values(), default=0)
    passed = total_steps >= steps_target

    return ShortWindowResult(
        passed=passed,
        total_steps=total_steps,
        steps_target=steps_target,
        origins=sorted(per_origin.keys()),
        records_counted=len(usable),
        outside_window=outside_window,
        dropped_flags=drop_counts,
    )
