"""SQLite facts survive API restarts; a committed claim is never launched twice."""

import json
import sqlite3
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from app.errors import AgentError

TERMINAL = frozenset({"SUCCESS", "RUNTIME_FAILED", "TIMEOUT", "CANCELLED"})


def utc_now() -> str:
    return datetime.now(UTC).isoformat()


class ProcessRegistry:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute(
                """CREATE TABLE IF NOT EXISTS executions (
                request_id TEXT PRIMARY KEY, external_id TEXT UNIQUE NOT NULL,
                fingerprint TEXT NOT NULL, request_json TEXT NOT NULL, workspace TEXT NOT NULL,
                state TEXT NOT NULL, pid INTEGER, create_time REAL, created_at TEXT NOT NULL,
                payload_json TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0
                )"""
            )
            db.execute(
                """CREATE TABLE IF NOT EXISTS callback_delivery (
                request_id TEXT PRIMARY KEY REFERENCES executions(request_id),
                delivered_fingerprint TEXT, delivered_generation TEXT, delivered_state TEXT,
                attempt_fingerprint TEXT, attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at REAL NOT NULL DEFAULT 0
                )"""
            )

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            yield db
        finally:
            db.close()

    def claim(
        self,
        request: dict,
        fingerprint: str,
        external_id: str,
        workspace: Path,
        cpu_total: int,
        memory_total_gb: float,
    ):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                existing = db.execute(
                    "SELECT * FROM executions WHERE request_id=?", (request["executionRequestId"],)
                ).fetchone()
                if existing:
                    if existing["fingerprint"] != fingerprint:
                        raise AgentError(
                            "IDEMPOTENCY_CONFLICT", "Execution request ID has different input", 409
                        )
                    db.commit()
                    return dict(existing), False
                used_cpu = 0
                used_memory = 0.0
                for active in db.execute(
                    "SELECT request_json FROM executions WHERE state NOT IN "
                    "('SUCCESS','RUNTIME_FAILED','TIMEOUT','CANCELLED')"
                ):
                    resources = json.loads(active["request_json"])["resource"]
                    used_cpu += resources["cpu"]
                    used_memory += resources["memoryGb"]
                resources = request["resource"]
                if (
                    used_cpu + resources["cpu"] > cpu_total
                    or used_memory + resources["memoryGb"] > memory_total_gb
                ):
                    raise AgentError("NO_RESOURCE", "Node runtime capacity is exhausted", 409)
                payload = {
                    "externalExecutionId": external_id,
                    "executionRequestId": request["executionRequestId"],
                    "state": "SUBMITTED",
                    "workspace": str(workspace),
                    "pid": None,
                    "processIdentity": None,
                    "exitCode": None,
                    "rawSolverCode": None,
                    "rawMessage": None,
                    "progress": 0,
                    "startedAt": None,
                    "finishedAt": None,
                }
                db.execute(
                    """INSERT INTO executions
                    (request_id,external_id,fingerprint,request_json,workspace,state,created_at,
                    payload_json) VALUES(?,?,?,?,?,'SUBMITTED',?,?)""",
                    (
                        request["executionRequestId"],
                        external_id,
                        fingerprint,
                        json.dumps(request, sort_keys=True),
                        str(workspace),
                        utc_now(),
                        json.dumps(payload),
                    ),
                )
                db.commit()
                return self.by_request(request["executionRequestId"]), True
            except BaseException:
                db.rollback()
                raise

    def by_request(self, request_id: str) -> dict | None:
        with self.connection() as db:
            row = db.execute(
                "SELECT * FROM executions WHERE request_id=?", (request_id,)
            ).fetchone()
            return dict(row) if row else None

    def by_external(self, external_id: str) -> dict | None:
        with self.connection() as db:
            row = db.execute(
                "SELECT * FROM executions WHERE external_id=?", (external_id,)
            ).fetchone()
            return dict(row) if row else None

    def all(self) -> list[dict]:
        with self.connection() as db:
            return [dict(row) for row in db.execute("SELECT * FROM executions")]

    def callback_candidates(
        self, after: str, generation: str, limit: int, terminal: bool
    ) -> list[dict]:
        """Read a bounded page; acknowledged terminal facts replay once per API startup."""
        with self.connection() as db:
            return [
                dict(row)
                for row in db.execute(
                    """SELECT e.request_id,e.external_id,d.delivered_fingerprint,
                    d.delivered_generation,d.attempt_fingerprint,d.attempts,d.next_attempt_at
                    FROM executions e LEFT JOIN callback_delivery d ON d.request_id=e.request_id
                    WHERE e.request_id>? AND ((?=0 AND e.state NOT IN
                    ('SUCCESS','RUNTIME_FAILED','TIMEOUT','CANCELLED'))
                    OR (?=1 AND e.state IN ('SUCCESS','RUNTIME_FAILED','TIMEOUT','CANCELLED') AND (
                    COALESCE(d.delivered_generation,'')<>?
                    OR COALESCE(d.delivered_state,'')<>e.state))) ORDER BY e.request_id LIMIT ?""",
                    (after, int(terminal), int(terminal), generation, limit),
                )
            ]

    def callback_delivered(
        self, request_id: str, fingerprint: str, generation: str, state: str
    ) -> None:
        with self.connection() as db:
            db.execute(
                """INSERT INTO callback_delivery
                (request_id,delivered_fingerprint,delivered_generation,attempt_fingerprint,
                delivered_state) VALUES(?,?,?,?,?) ON CONFLICT(request_id) DO UPDATE SET
                delivered_fingerprint=excluded.delivered_fingerprint,
                delivered_generation=excluded.delivered_generation,
                attempt_fingerprint=excluded.attempt_fingerprint,
                delivered_state=excluded.delivered_state,attempts=0,next_attempt_at=0""",
                (request_id, fingerprint, generation, fingerprint, state),
            )

    def callback_failed(
        self, request_id: str, fingerprint: str, attempts: int, next_attempt_at: float
    ) -> None:
        with self.connection() as db:
            db.execute(
                """INSERT INTO callback_delivery
                (request_id,attempt_fingerprint,attempts,next_attempt_at) VALUES(?,?,?,?)
                ON CONFLICT(request_id) DO UPDATE SET
                attempt_fingerprint=excluded.attempt_fingerprint,
                attempts=excluded.attempts,next_attempt_at=excluded.next_attempt_at""",
                (request_id, fingerprint, attempts, next_attempt_at),
            )

    def update_payload(self, request_id: str, payload: dict) -> None:
        with self.connection() as db:
            db.execute(
                """UPDATE executions SET state=?, payload_json=?,pid=COALESCE(?,pid),
                create_time=COALESCE(?,create_time) WHERE request_id=?
                AND (state NOT IN ('SUCCESS','RUNTIME_FAILED','TIMEOUT','CANCELLED')
                OR ? IN ('SUCCESS','RUNTIME_FAILED','TIMEOUT','CANCELLED'))""",
                (
                    payload["state"],
                    json.dumps(payload),
                    payload.get("pid"),
                    payload.get("createTime"),
                    request_id,
                    payload["state"],
                ),
            )

    def mark_cancel(self, request_id: str) -> None:
        with self.connection() as db:
            db.execute("UPDATE executions SET cancel_requested=1 WHERE request_id=?", (request_id,))
