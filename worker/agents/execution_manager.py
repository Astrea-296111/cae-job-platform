"""Claim once, run independently, reconcile from durable child state."""

import hashlib
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import uuid
from datetime import UTC, datetime
from pathlib import Path

import psutil

from adapters.profiles import validate_profile
from agents.files import atomic_json, ensure_contained, verify_snapshot
from agents.process_registry import TERMINAL, ProcessRegistry, utc_now
from app.contracts import SubmitRequest
from app.errors import AgentError
from app.settings import Settings


class ExecutionManager:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.registry = ProcessRegistry(settings.registry_path)
        self._launch_lock = threading.RLock()
        self._children: dict[int, subprocess.Popen] = {}

    def submit(self, request: SubmitRequest) -> tuple[dict, bool]:
        encoded = request.model_dump(by_alias=True, mode="json")
        fingerprint = hashlib.sha256(json.dumps(encoded, sort_keys=True).encode()).hexdigest()
        # A duplicate request can recover a terminal execution even if external input disappeared.
        existing = self.registry.by_request(request.execution_request_id)
        if existing:
            if existing["fingerprint"] != fingerprint:
                raise AgentError(
                    "IDEMPOTENCY_CONFLICT", "Execution request ID has different input", 409
                )
            return self.query(existing["external_id"]), False
        validate_profile(request, self.settings)
        workspace, manifest = verify_snapshot(request, self.settings.data_root)
        output = workspace / "executions" / request.execution_request_id
        ensure_contained(self.settings.data_root, output)
        with self._launch_lock:
            self.node_status()  # Refresh completed child facts before atomically counting capacity.
            row, claimed = self.registry.claim(
                encoded,
                fingerprint,
                f"{self.settings.node_id}-{uuid.uuid4().hex}",
                output,
                self.settings.cpu_total,
                self.settings.memory_total_gb,
            )
            if not claimed:
                return self.query(row["external_id"]), False
            # This claim is already durable. Failure here is recorded, never silently re-launched.
            try:
                output.mkdir(parents=True, exist_ok=True)
                ensure_contained(self.settings.data_root, output)
                config = {
                    **encoded,
                    "workspace": str(output),
                    "inputDirectory": str(workspace / "input"),
                    "externalExecutionId": row["external_id"],
                    "nodeId": self.settings.node_id,
                    "parameters": manifest.get("parameters", {}),
                }
                atomic_json(output / "execution-config.json", config)
                if self.registry.by_request(request.execution_request_id)["cancel_requested"]:
                    atomic_json(output / "cancel.request", {"requestedAt": utc_now()})
                with (output / "solver.log").open("ab") as log:
                    child = subprocess.Popen(
                        [
                            sys.executable,
                            "-m",
                            "mock_solver",
                            "--config",
                            str(output / "execution-config.json"),
                        ],
                        cwd=Path(__file__).resolve().parents[1],
                        stdin=subprocess.DEVNULL,
                        stdout=log,
                        stderr=log,
                        start_new_session=True,
                        close_fds=True,
                    )
                self._children[child.pid] = child
                payload = json.loads(row["payload_json"])
                create_time = psutil.Process(child.pid).create_time()
                payload.update(
                    pid=child.pid,
                    createTime=create_time,
                    processIdentity=f"{child.pid}:{create_time:.6f}",
                )
                self.registry.update_payload(request.execution_request_id, payload)
                return self.query(row["external_id"]), True
            except OSError as error:
                payload = json.loads(row["payload_json"])
                payload.update(
                    state="RUNTIME_FAILED",
                    exitCode=-1,
                    rawSolverCode="AGENT_LAUNCH_FAILURE",
                    rawMessage=f"Mock process could not start: {type(error).__name__}",
                    finishedAt=utc_now(),
                )
                self.registry.update_payload(request.execution_request_id, payload)
                raise AgentError(
                    "SUBMIT_REJECTED", "Mock solver process could not start", 503
                ) from error

    @staticmethod
    def _matching_process(pid: int | None, create_time: float | None) -> psutil.Process | None:
        if pid is None or create_time is None:
            return None
        try:
            process = psutil.Process(pid)
            if (
                abs(process.create_time() - create_time) > 0.001
                or process.status() == psutil.STATUS_ZOMBIE
            ):
                return None
            return process
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            return None

    def query(self, external_id: str) -> dict:
        row = self.registry.by_external(external_id)
        if not row:
            raise AgentError("EXECUTION_NOT_FOUND", "Execution is not present on this node", 404)
        payload = json.loads(row["payload_json"])
        if payload["state"] not in TERMINAL:
            state_file = Path(row["workspace"]) / "state.json"
            if state_file.is_file():
                try:
                    child_state = json.loads(state_file.read_text(encoding="utf-8"))
                    if child_state.get("externalExecutionId") == external_id:
                        payload = child_state
                except (OSError, ValueError):
                    pass  # Atomic file writer makes this rare; preserve previous runtime facts.
            process = self._matching_process(payload.get("pid"), payload.get("createTime"))
            age = (datetime.now(UTC) - datetime.fromisoformat(row["created_at"])).total_seconds()
            if payload["state"] not in TERMINAL and not process and age > 5:
                payload.update(
                    state="UNKNOWN", rawMessage="No matching live solver identity or terminal state"
                )
            self.registry.update_payload(row["request_id"], payload)
        for pid, child in list(self._children.items()):
            if child.poll() is not None:
                self._children.pop(pid, None)
        payload.pop("createTime", None)
        payload["cancelRequested"] = bool(row["cancel_requested"])
        return payload

    def query_by_request(self, request_id: str) -> dict:
        row = self.registry.by_request(request_id)
        if not row:
            raise AgentError(
                "EXECUTION_NOT_FOUND", "Execution request is not present on this node", 404
            )
        return self.query(row["external_id"])

    def cancel(self, external_id: str) -> dict:
        with self._launch_lock:
            payload = self.query(external_id)
            if payload["state"] in TERMINAL:
                return payload
            row = self.registry.by_external(external_id)
            self.registry.mark_cancel(row["request_id"])
            output = Path(row["workspace"])
            ensure_contained(self.settings.data_root, output)
            output.mkdir(parents=True, exist_ok=True)
            atomic_json(output / "cancel.request", {"requestedAt": utc_now()})
            # A cancellation marker also protects the window before a child's signal handler exists.
            process = self._matching_process(row["pid"], row["create_time"])
            if process and payload.get("startedAt"):
                try:
                    if os.getpgid(process.pid) == process.pid:
                        os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            return self.query(external_id)

    def node_status(self) -> dict:
        active = []
        cpu_used = 0
        memory_used = 0.0
        for row in self.registry.all():
            state = self.query(row["external_id"])
            if state["state"] not in TERMINAL:
                request = json.loads(row["request_json"])
                active.append(row["external_id"])
                cpu_used += request["resource"]["cpu"]
                memory_used += request["resource"]["memoryGb"]
        root = self.settings.data_root
        jobs_root = root / "jobs"
        required_access = os.R_OK | os.W_OK | os.X_OK
        workspace_reachable = (
            root.is_dir()
            and not root.is_symlink()
            and os.access(root, required_access)
            and (
                not jobs_root.exists()
                or (
                    jobs_root.is_dir()
                    and not jobs_root.is_symlink()
                    and os.access(jobs_root, required_access)
                )
            )
        )
        return {
            "nodeId": self.settings.node_id,
            "hostname": socket.gethostname(),
            "online": True,
            "workspaceReachable": workspace_reachable,
            "cpuTotal": self.settings.cpu_total,
            "cpuUsed": cpu_used,
            "cpuReserved": 0,
            "memoryTotalGb": self.settings.memory_total_gb,
            "memoryUsedGb": memory_used,
            "memoryReservedGb": 0,
            "solverTags": list(self.settings.solver_tags),
            "runningJobs": active,
            "lastHeartbeat": utc_now(),
        }
