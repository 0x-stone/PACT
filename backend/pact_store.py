"""SQLite persistence for the community fitness pact state.

Four tables (the pact's own data, kept separate from the tester's uploads
DB so wiping test uploads never touches pact history):

- members         one row per member; timezone locked at first ingest
- challenges      one row per pact week; immutable rules snapshot
- daily_results   per member per day aggregates (derived data, never raw
                  health records - raw payloads stay in the uploads DB)
- weekly_results  per member per week verdict (days_completed, passed)

The database file defaults to backend/data/pact.db and can be overridden
with the HEALTH_SYNC_PACT_DB environment variable. Unlike storage.py, the
override is read on every connection so it also applies to already-imported
modules (keeps tests deterministic regardless of import order).
"""

from __future__ import annotations

import json
import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterator, List, Optional

from pact.eligibility import DailyResult, WeekEvaluation
from pact.rules import PactRules

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = BASE_DIR / "data" / "pact.db"


class TimezoneLockedError(ValueError):
    """A member tried to change their locked day-boundary timezone."""


def _db_path() -> Path:
    return Path(os.getenv("HEALTH_SYNC_PACT_DB", str(DEFAULT_DB_PATH))).expanduser()


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def _db() -> Iterator[sqlite3.Connection]:
    db_path = _db_path()
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path), timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=5000")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_pact_db() -> None:
    """Create the pact tables if they do not exist."""
    with _db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS challenges (
                challenge_id INTEGER PRIMARY KEY AUTOINCREMENT,
                contract_id TEXT NOT NULL DEFAULT 'pactcontract.testnet',
                week_start TEXT NOT NULL,
                rules_json TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'active',
                created_at TEXT NOT NULL
            )
            """
        )
        try:
            conn.execute("ALTER TABLE challenges ADD COLUMN contract_id TEXT NOT NULL DEFAULT 'pactcontract.testnet'")
        except sqlite3.OperationalError:
            pass
        conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_challenges_contract_week ON challenges(contract_id, week_start)"
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS members (
                member_id TEXT PRIMARY KEY,
                challenge_id INTEGER NOT NULL,
                timezone TEXT NOT NULL,
                joined_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_members_challenge ON members(challenge_id)"
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS daily_results (
                challenge_id INTEGER NOT NULL,
                member_id TEXT NOT NULL,
                day_date TEXT NOT NULL,
                steps INTEGER NOT NULL,
                day_passed INTEGER NOT NULL,
                flags_json TEXT NOT NULL,
                origins_json TEXT NOT NULL,
                computed_at TEXT NOT NULL,
                PRIMARY KEY (challenge_id, member_id, day_date)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS weekly_results (
                challenge_id INTEGER NOT NULL,
                member_id TEXT NOT NULL,
                days_completed INTEGER NOT NULL,
                passed INTEGER NOT NULL,
                flags_json TEXT NOT NULL,
                evaluated_at TEXT NOT NULL,
                PRIMARY KEY (challenge_id, member_id)
            )
            """
        )


def get_or_create_challenge(
    week_start: str,
    rules: PactRules,
    contract_id: str = "pactcontract.testnet",
) -> Dict[str, object]:
    """Return the challenge for this week, snapshotting the current rules once.

    The snapshot is immutable afterwards: a later edit to rules.json must
    not retroactively change an already-open week's terms.
    Keyed by (contract_id, week_start).
    """
    init_pact_db()
    with _db() as conn:
        row = conn.execute(
            "SELECT * FROM challenges WHERE contract_id = ? AND week_start = ?",
            (contract_id, week_start),
        ).fetchone()
        if row is not None:
            return _row_to_challenge(row)
        cursor = conn.execute(
            """
            INSERT INTO challenges (contract_id, week_start, rules_json, status, created_at)
            VALUES (?, ?, ?, 'active', ?)
            """,
            (contract_id, week_start, rules.model_dump_json(), _now_iso()),
        )
        return {
            "challengeId": cursor.lastrowid,
            "contractId": contract_id,
            "weekStart": week_start,
            "rulesJson": rules.model_dump_json(),
            "status": "active",
            "createdAt": _now_iso(),
        }


def get_active_challenge(contract_id: Optional[str] = None) -> Optional[Dict[str, object]]:
    """The newest challenge by week_start for the contract, or None if none exists yet."""
    init_pact_db()
    with _db() as conn:
        if contract_id:
            row = conn.execute(
                "SELECT * FROM challenges WHERE contract_id = ? ORDER BY week_start DESC, challenge_id DESC LIMIT 1",
                (contract_id,),
            ).fetchone()
        else:
            row = conn.execute(
                "SELECT * FROM challenges ORDER BY week_start DESC, challenge_id DESC LIMIT 1"
            ).fetchone()
    if row is None:
        return None
    return _row_to_challenge(row)


def _row_to_challenge(row) -> Dict[str, object]:
    col_keys = row.keys() if hasattr(row, "keys") else []
    return {
        "challengeId": row["challenge_id"],
        "contractId": row["contract_id"] if "contract_id" in col_keys else "pactcontract.testnet",
        "weekStart": row["week_start"],
        "rulesJson": row["rules_json"],
        "status": row["status"],
        "createdAt": row["created_at"],
    }


def get_challenge_by_week_start(week_start: str, contract_id: Optional[str] = None) -> Optional[Dict[str, object]]:
    """Look up one challenge week by its contract_id and ISO week-start date."""
    init_pact_db()
    with _db() as conn:
        if contract_id:
            row = conn.execute(
                "SELECT * FROM challenges WHERE contract_id = ? AND week_start = ?",
                (contract_id, week_start),
            ).fetchone()
        else:
            row = conn.execute(
                "SELECT * FROM challenges WHERE week_start = ?", (week_start,)
            ).fetchone()
    return None if row is None else _row_to_challenge(row)


def get_challenge_by_id(challenge_id: int) -> Optional[Dict[str, object]]:
    """Look up one challenge week by its database id."""
    init_pact_db()
    with _db() as conn:
        row = conn.execute(
            "SELECT * FROM challenges WHERE challenge_id = ?", (challenge_id,)
        ).fetchone()
    return None if row is None else _row_to_challenge(row)


def get_weekly_result(challenge_id: int, member_id: str) -> Optional[Dict[str, object]]:
    """One member's stored weekly verdict (used by the attestation endpoint)."""
    init_pact_db()
    with _db() as conn:
        row = conn.execute(
            """
            SELECT days_completed, passed, flags_json, evaluated_at
            FROM weekly_results WHERE challenge_id = ? AND member_id = ?
            """,
            (challenge_id, member_id),
        ).fetchone()
    if row is None:
        return None
    return {
        "daysCompleted": row["days_completed"],
        "passed": bool(row["passed"]),
        "flagsJson": row["flags_json"],
        "evaluatedAt": row["evaluated_at"],
    }


def get_member(member_id: str) -> Optional[Dict[str, object]]:
    init_pact_db()
    with _db() as conn:
        row = conn.execute(
            "SELECT * FROM members WHERE member_id = ?", (member_id,)
        ).fetchone()
    if row is None:
        return None
    return {
        "memberId": row["member_id"],
        "challengeId": row["challenge_id"],
        "timezone": row["timezone"],
        "joinedAt": row["joined_at"],
    }


def upsert_member(member_id: str, challenge_id: int, timezone: str) -> Dict[str, object]:
    """Register/re-enroll a member. Timezone is locked after first ingest.

    The member's challenge_id moves to the week they last submitted to, so
    'enrolled' means 'active in the pact's current week'. Old weekly_results
    rows keep the original challenge_id, preserving history.
    """
    init_pact_db()
    existing = get_member(member_id)
    if existing is not None:
        if existing["timezone"] != timezone:
            raise TimezoneLockedError(
                f"member {member_id!r} timezone is locked to "
                f"{existing['timezone']!r}; got {timezone!r}"
            )
        with _db() as conn:
            conn.execute(
                "UPDATE members SET challenge_id = ? WHERE member_id = ?",
                (challenge_id, member_id),
            )
    else:
        with _db() as conn:
            conn.execute(
                "INSERT INTO members (member_id, challenge_id, timezone, joined_at)"
                " VALUES (?, ?, ?, ?)",
                (member_id, challenge_id, timezone, _now_iso()),
            )
    return get_member(member_id)  # type: ignore[return-value]


def replace_daily_results(
    challenge_id: int, member_id: str, daily: List[DailyResult]
) -> None:
    """Rewrite one member's week. The pipeline always emits all 7 days, so
    replace-not-merge keeps stored state exactly equal to the latest upload."""
    init_pact_db()
    computed_at = _now_iso()
    with _db() as conn:
        conn.execute(
            "DELETE FROM daily_results WHERE challenge_id = ? AND member_id = ?",
            (challenge_id, member_id),
        )
        conn.executemany(
            """
            INSERT INTO daily_results (
                challenge_id, member_id, day_date, steps, day_passed,
                flags_json, origins_json, computed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (
                    challenge_id,
                    member_id,
                    row.day_date.isoformat(),
                    row.steps,
                    int(row.day_passed),
                    json.dumps(row.flags, ensure_ascii=False),
                    json.dumps(row.origins, ensure_ascii=False),
                    computed_at,
                )
                for row in daily
            ],
        )


def upsert_weekly_result(
    challenge_id: int, member_id: str, evaluation: WeekEvaluation
) -> None:
    init_pact_db()
    with _db() as conn:
        conn.execute(
            """
            INSERT INTO weekly_results (
                challenge_id, member_id, days_completed, passed, flags_json, evaluated_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(challenge_id, member_id) DO UPDATE SET
                days_completed = excluded.days_completed,
                passed = excluded.passed,
                flags_json = excluded.flags_json,
                evaluated_at = excluded.evaluated_at
            """,
            (
                challenge_id,
                member_id,
                evaluation.days_completed,
                int(evaluation.passed),
                evaluation.model_dump_json(),
                _now_iso(),
            ),
        )


def community_days(challenge_id: int) -> Dict[str, int]:
    """Days completed per member for one challenge (members without a
    verdict yet count as 0). Community numbers only - never exposed per
    member through any endpoint."""
    init_pact_db()
    with _db() as conn:
        rows = conn.execute(
            """
            SELECT m.member_id AS member_id,
                   COALESCE(w.days_completed, 0) AS days_completed
            FROM members m
            LEFT JOIN weekly_results w
              ON w.member_id = m.member_id AND w.challenge_id = m.challenge_id
            WHERE m.challenge_id = ?
            """,
            (challenge_id,),
        ).fetchall()
    return {row["member_id"]: int(row["days_completed"]) for row in rows}


def clear_pact_db() -> int:
    """Delete every pact row (test helper). Returns removed member count."""
    init_pact_db()
    with _db() as conn:
        members = conn.execute("SELECT COUNT(*) AS c FROM members").fetchone()["c"]
        conn.execute("DELETE FROM daily_results")
        conn.execute("DELETE FROM weekly_results")
        conn.execute("DELETE FROM members")
        conn.execute("DELETE FROM challenges")
        return members

def get_member_weekly_results(member_id: str) -> list:
    """All weekly results for one member (used by reputation endpoint).

    Returns a list of dicts with keys: challenge_id, week_start, days_completed,
    passed, evaluated_at. No raw health data — derived verdicts only.
    """
    init_pact_db()
    with _db() as conn:
        rows = conn.execute(
            """
            SELECT w.challenge_id, c.week_start, w.days_completed,
                   w.passed, w.evaluated_at
            FROM   weekly_results w
            JOIN   challenges c ON c.challenge_id = w.challenge_id
            WHERE  w.member_id = ?
            ORDER  BY c.week_start
            """,
            (member_id,),
        ).fetchall()
    return [
        {
            "challenge_id":   row["challenge_id"],
            "week_start":     row["week_start"],
            "days_completed": row["days_completed"],
            "passed":         bool(row["passed"]),
            "evaluated_at":   row["evaluated_at"],
        }
        for row in rows
    ]
