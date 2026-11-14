"""
SQLite persistence for Health Connect sync uploads.

The database file defaults to backend/data/health_sync.db and can be
overridden with the HEALTH_SYNC_DB environment variable.

Since NEAR sign-in landed, every upload row carries the NEAR account id of
the authenticated member (``account_id``; NULL for legacy pre-auth rows,
which stay admin-visible only). The query helpers accept an optional
``account_id`` to scope reads to one member — the privacy-preserving mode
the Bearer-authenticated endpoints use.
"""

from __future__ import annotations

import json
import os
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = BASE_DIR / "data" / "health_sync.db"
DB_PATH = Path(os.getenv("HEALTH_SYNC_DB", str(DEFAULT_DB_PATH))).expanduser()


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def _db() -> Iterator[sqlite3.Connection]:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH), timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=5000")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    """Create the uploads table if it does not exist."""
    with _db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS uploads (
                upload_id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                sync_timestamp TEXT NOT NULL,
                received_at TEXT NOT NULL,
                record_count INTEGER NOT NULL,
                summary_json TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                account_id TEXT
            )
            """
        )
        # Migration for databases created before NEAR sign-in: add the
        # account attribution column. Legacy rows keep NULL (admin view).
        columns = {
            row["name"]
            for row in conn.execute("PRAGMA table_info(uploads)").fetchall()
        }
        if "account_id" not in columns:
            conn.execute("ALTER TABLE uploads ADD COLUMN account_id TEXT")
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_uploads_received_at "
            "ON uploads(received_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_uploads_device_id "
            "ON uploads(device_id)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_uploads_account_id "
            "ON uploads(account_id)"
        )


def summarize_records(records: List[Dict[str, Any]]) -> Dict[str, int]:
    """Return counts grouped by recordType, computed from the records array."""
    summary: Dict[str, int] = {}
    for record in records:
        record_type = str(record.get("recordType", "UnknownRecord"))
        summary[record_type] = summary.get(record_type, 0) + 1
    return summary


def save_upload(payload: Dict[str, Any], account_id: Optional[str] = None) -> Dict[str, Any]:
    """Persist one sync payload, attributed to a NEAR account when known."""
    init_db()

    records = payload.get("records") or []
    upload_id = uuid.uuid4().hex
    received_at = _now_iso()
    device_id = str(payload.get("deviceId") or "unknown")
    sync_timestamp = str(payload.get("syncTimestamp") or received_at)
    summary = summarize_records(records)

    with _db() as conn:
        conn.execute(
            """
            INSERT INTO uploads (
                upload_id, device_id, sync_timestamp, received_at,
                record_count, summary_json, payload_json, account_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                upload_id,
                device_id,
                sync_timestamp,
                received_at,
                len(records),
                json.dumps(summary, ensure_ascii=False),
                json.dumps(payload, ensure_ascii=False),
                account_id,
            ),
        )

    return {
        "uploadId": upload_id,
        "deviceId": device_id,
        "accountId": account_id,
        "syncTimestamp": sync_timestamp,
        "receivedAt": received_at,
        "recordCount": len(records),
        "summary": summary,
        "retrievalUrl": f"/api/health/uploads/{upload_id}",
    }


def _row_to_upload(row: sqlite3.Row) -> Dict[str, Any]:
    return {
        "uploadId": row["upload_id"],
        "deviceId": row["device_id"],
        "accountId": row["account_id"],
        "syncTimestamp": row["sync_timestamp"],
        "receivedAt": row["received_at"],
        "recordCount": row["record_count"],
        "summary": json.loads(row["summary_json"]),
        "retrievalUrl": f"/api/health/uploads/{row['upload_id']}",
    }


def list_uploads(
    device_id: Optional[str] = None,
    record_type: Optional[str] = None,
    limit: int = 50,
    offset: int = 0,
    account_id: Optional[str] = None,
) -> Dict[str, Any]:
    """List stored uploads, newest first.

    With ``account_id`` (the Bearer-authenticated view) only that member's
    uploads are visible; NULL rows from before sign-in existed stay
    admin-visible only.
    """
    init_db()

    query = "SELECT * FROM uploads"
    clauses: List[str] = []
    params: List[Any] = []
    if account_id is not None:
        clauses.append("account_id = ?")
        params.append(account_id)
    if device_id:
        clauses.append("device_id = ?")
        params.append(device_id)
    if clauses:
        query += " WHERE " + " AND ".join(clauses)
    query += " ORDER BY received_at DESC, rowid DESC"

    with _db() as conn:
        rows = conn.execute(query, params).fetchall()

    uploads = [_row_to_upload(row) for row in rows]
    if record_type:
        uploads = [
            upload
            for upload in uploads
            if upload["summary"].get(record_type, 0) > 0
        ]

    total = len(uploads)
    return {
        "count": total,
        "limit": limit,
        "offset": offset,
        "uploads": uploads[offset : offset + limit],
    }


def get_upload(upload_id: str, account_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
    """Return one stored upload including the full original payload."""
    init_db()

    with _db() as conn:
        row = conn.execute(
            "SELECT * FROM uploads WHERE upload_id = ?", (upload_id,)
        ).fetchone()

    if row is None:
        return None
    if account_id is not None and row["account_id"] != account_id:
        return None  # exists, but not this member's: indistinguishable from missing

    upload = _row_to_upload(row)
    upload["payload"] = json.loads(row["payload_json"])
    return upload


def list_records(
    device_id: Optional[str] = None,
    record_type: Optional[str] = None,
    upload_id: Optional[str] = None,
    limit: int = 100,
    offset: int = 0,
    account_id: Optional[str] = None,
) -> Dict[str, Any]:
    """Flatten every stored record, with optional filters."""
    init_db()

    clauses: List[str] = []
    params: List[Any] = []
    if account_id is not None:
        clauses.append("account_id = ?")
        params.append(account_id)
    if device_id:
        clauses.append("device_id = ?")
        params.append(device_id)
    if upload_id:
        clauses.append("upload_id = ?")
        params.append(upload_id)

    query = "SELECT upload_id, device_id, received_at, payload_json FROM uploads"
    if clauses:
        query += " WHERE " + " AND ".join(clauses)
    query += " ORDER BY received_at DESC, rowid DESC"

    with _db() as conn:
        rows = conn.execute(query, params).fetchall()

    records: List[Dict[str, Any]] = []
    for row in rows:
        payload = json.loads(row["payload_json"])
        for record in payload.get("records") or []:
            if record_type and record.get("recordType") != record_type:
                continue
            item: Dict[str, Any] = {
                "uploadId": row["upload_id"],
                "deviceId": row["device_id"],
                "receivedAt": row["received_at"],
            }
            item.update(record)
            records.append(item)

    records.sort(key=lambda item: str(item.get("startTime") or ""), reverse=True)
    total = len(records)
    return {
        "count": total,
        "limit": limit,
        "offset": offset,
        "records": records[offset : offset + limit],
    }


def get_stats(account_id: Optional[str] = None) -> Dict[str, Any]:
    """Return aggregate counts for the dashboard (optionally per member)."""
    init_db()

    where = "WHERE account_id = ?" if account_id is not None else ""
    params: List[Any] = [account_id] if account_id is not None else []
    with _db() as conn:
        row = conn.execute(
            f"""
            SELECT
                COUNT(*) AS uploads,
                COALESCE(SUM(record_count), 0) AS records,
                COUNT(DISTINCT device_id) AS devices,
                MAX(received_at) AS latest_received_at
            FROM uploads {where}
            """,
            params,
        ).fetchone()
        summary_rows = conn.execute(
            f"SELECT summary_json FROM uploads {where}", params
        ).fetchall()

    by_record_type: Dict[str, int] = {}
    for summary_row in summary_rows:
        for record_type, count in json.loads(summary_row["summary_json"]).items():
            by_record_type[record_type] = (
                by_record_type.get(record_type, 0) + int(count)
            )

    return {
        "uploads": row["uploads"],
        "records": row["records"],
        "devices": row["devices"],
        "byRecordType": by_record_type,
        "latestReceivedAt": row["latest_received_at"],
    }


def delete_upload(upload_id: str, account_id: Optional[str] = None) -> bool:
    """Delete one upload. Returns True if a row was removed."""
    init_db()

    with _db() as conn:
        if account_id is not None:
            cursor = conn.execute(
                "DELETE FROM uploads WHERE upload_id = ? AND account_id = ?",
                (upload_id, account_id),
            )
        else:
            cursor = conn.execute(
                "DELETE FROM uploads WHERE upload_id = ?", (upload_id,)
            )
        return cursor.rowcount > 0


def clear_uploads(account_id: Optional[str] = None) -> int:
    """Delete every stored upload (or one member's). Returns the number of
    removed rows."""
    init_db()

    with _db() as conn:
        if account_id is not None:
            cursor = conn.execute(
                "DELETE FROM uploads WHERE account_id = ?", (account_id,)
            )
        else:
            cursor = conn.execute("DELETE FROM uploads")
        return cursor.rowcount
