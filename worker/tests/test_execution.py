import json
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import psutil
import pytest
from conftest import prepare_request, wait_terminal

from agents.execution_manager import ExecutionManager
from agents.files import aggregate_checksum, atomic_json, sha256_file
from agents.process_registry import utc_now
from app.errors import AgentError


@pytest.mark.parametrize("analysis", ["STRUCTURE", "NVH", "CRASH", "CFD"])
def test_success_produces_real_process_and_deterministic_raw_output(manager, settings, analysis):
    request = prepare_request(settings.data_root, analysis=analysis)
    state, claimed = manager.submit(request)
    assert claimed
    assert state["pid"] != psutil.Process().pid
    assert state["processIdentity"]
    terminal = wait_terminal(manager, state["externalExecutionId"])
    assert terminal["state"] == "SUCCESS"
    assert terminal["exitCode"] == 0
    raw = json.loads(Path(terminal["workspace"], "raw_result.json").read_text())
    assert raw["analysisType"] == analysis
    assert raw["conditionCode"] == request.condition_code
    assert raw["seed"] == 42
    assert Path(terminal["workspace"], "progress.jsonl").stat().st_size > 0
    assert "SUCCESS" in Path(terminal["workspace"], "solver.log").read_text()
    another = prepare_request(settings.data_root, number=2, analysis=analysis)
    second, _ = manager.submit(another)
    second = wait_terminal(manager, second["externalExecutionId"])
    assert json.loads(Path(second["workspace"], "raw_result.json").read_text()) == raw


@pytest.mark.parametrize("mode,code", [("RUNTIME_FAILED", 2), ("TIMEOUT", 124)])
def test_solver_failure_and_timeout_keep_raw_reason(manager, settings, mode, code):
    request = prepare_request(settings.data_root, failureMode=mode)
    state, _ = manager.submit(request)
    terminal = wait_terminal(manager, state["externalExecutionId"])
    assert terminal["state"] == mode
    assert terminal["exitCode"] == code
    assert terminal["rawSolverCode"]
    assert terminal["rawMessage"]
    assert not Path(terminal["workspace"], "raw_result.json").exists()


def test_walltime_is_enforced_without_fault_mode(manager, settings):
    request = prepare_request(settings.data_root, runtimeSeconds=2)
    request.resource.walltime_min = 0.001
    state, _ = manager.submit(request)
    terminal = wait_terminal(manager, state["externalExecutionId"])
    assert terminal["state"] == "TIMEOUT"
    assert terminal["rawSolverCode"] == "MOCK_WALLTIME_EXCEEDED"


def test_concurrent_duplicates_launch_exactly_one_solver(manager, settings):
    request = prepare_request(settings.data_root, runtimeSeconds=0.7)
    with ThreadPoolExecutor(max_workers=16) as pool:
        replies = list(pool.map(lambda _: manager.submit(request), range(32)))
    assert len({reply[0]["externalExecutionId"] for reply in replies}) == 1
    assert sum(claimed for _, claimed in replies) == 1
    assert len(manager.registry.all()) == 1
    state = wait_terminal(manager, replies[0][0]["externalExecutionId"])
    assert Path(state["workspace"], "solver.log").read_text().count("Mock Solver RUNNING") == 1


def test_request_id_conflict_and_completed_duplicate(manager, settings):
    request = prepare_request(settings.data_root)
    state, _ = manager.submit(request)
    completed = wait_terminal(manager, state["externalExecutionId"])
    replay, claimed = manager.submit(request)
    assert not claimed
    assert replay["externalExecutionId"] == completed["externalExecutionId"]
    assert replay["state"] == "SUCCESS"
    request.mock.seed = 100
    with pytest.raises(AgentError, match="different input") as conflict:
        manager.submit(request)
    assert conflict.value.status == 409


@pytest.mark.parametrize("delay", [0, 0.3])
def test_cancel_before_child_initialization_and_running(manager, settings, delay):
    request = prepare_request(settings.data_root, runtimeSeconds=2)
    state, _ = manager.submit(request)
    time.sleep(delay)
    manager.cancel(state["externalExecutionId"])
    terminal = wait_terminal(manager, state["externalExecutionId"])
    assert terminal["state"] == "CANCELLED"
    assert manager.cancel(state["externalExecutionId"])["state"] == "CANCELLED"
    assert manager.node_status()["runningJobs"] == []


def test_success_cannot_be_cancelled(manager, settings):
    state, _ = manager.submit(prepare_request(settings.data_root))
    terminal = wait_terminal(manager, state["externalExecutionId"])
    assert manager.cancel(state["externalExecutionId"])["state"] == "SUCCESS"
    assert not Path(terminal["workspace"], "cancel.request").exists()


def test_registry_restart_recovers_running_process_without_launch(manager, settings):
    request = prepare_request(settings.data_root, runtimeSeconds=1)
    state, _ = manager.submit(request)
    original_pid = state["pid"]
    restarted = ExecutionManager(settings)
    recovered, claimed = restarted.submit(request)
    assert not claimed
    assert recovered["pid"] == original_pid
    assert recovered["externalExecutionId"] == state["externalExecutionId"]
    assert wait_terminal(restarted, state["externalExecutionId"])["state"] == "SUCCESS"
    assert manager.query(state["externalExecutionId"])["state"] == "SUCCESS"


def test_ambiguous_claim_is_never_relaunched(manager, settings):
    request = prepare_request(settings.data_root)
    import hashlib

    encoded = request.model_dump(by_alias=True, mode="json")
    fingerprint = hashlib.sha256(json.dumps(encoded, sort_keys=True).encode()).hexdigest()
    workspace = Path(request.workspace) / "executions" / request.execution_request_id
    row, _ = manager.registry.claim(encoded, fingerprint, "node-a-orphan", workspace, 16, 32)
    replay, claimed = manager.submit(request)
    assert not claimed
    assert replay["pid"] is None
    # Simulate time passing after a crash between durable claim and actual launch.
    with manager.registry.connection() as db:
        db.execute("UPDATE executions SET created_at='2020-01-01T00:00:00+00:00'")
    assert manager.query(row["external_id"])["state"] == "UNKNOWN"
    # Test cleanup must not pretend an unlaunched claim has an acknowledged cancellation.
    payload = manager.query(row["external_id"])
    payload.update(state="CANCELLED", finishedAt=utc_now())
    manager.registry.update_payload(row["request_id"], payload)


def test_cancel_never_signals_reused_pid_identity(manager, settings):
    request = prepare_request(settings.data_root)
    sleeper = subprocess.Popen(
        [sys.executable, "-c", "import time;time.sleep(30)"], start_new_session=True
    )
    try:
        encoded = request.model_dump(by_alias=True, mode="json")
        workspace = Path(request.workspace) / "executions" / request.execution_request_id
        workspace.mkdir(parents=True)
        row, _ = manager.registry.claim(encoded, "fake", "node-a-reused", workspace, 16, 32)
        payload = json.loads(row["payload_json"])
        payload.update(
            state="RUNNING",
            pid=sleeper.pid,
            createTime=psutil.Process(sleeper.pid).create_time() + 100,
            startedAt=utc_now(),
        )
        manager.registry.update_payload(row["request_id"], payload)
        manager.cancel(row["external_id"])
        assert sleeper.poll() is None
        payload.update(state="CANCELLED", finishedAt=utc_now())
        manager.registry.update_payload(row["request_id"], payload)
    finally:
        sleeper.terminate()
        sleeper.wait(timeout=5)


def test_atomic_node_capacity_guard(manager, settings):
    requests = [
        prepare_request(settings.data_root, number=i, runtimeSeconds=2) for i in range(1, 17)
    ]

    def submit(request):
        try:
            return manager.submit(request)[0]
        except AgentError as error:
            assert error.code == "NO_RESOURCE"
            return None

    with ThreadPoolExecutor(max_workers=16) as pool:
        responses = list(pool.map(submit, requests))
    accepted = [response for response in responses if response]
    assert len(accepted) == 4
    status = manager.node_status()
    assert status["cpuUsed"] == 16
    assert status["memoryUsedGb"] == 16
    # Existing executions remain retrievable while this node is full.
    accepted_request = next(
        request
        for request in requests
        if request.execution_request_id == accepted[0]["executionRequestId"]
    )
    replay, claimed = manager.submit(accepted_request)
    assert not claimed
    assert replay["externalExecutionId"] in status["runningJobs"]


@pytest.mark.parametrize(
    "damage", ["checksum", "extra", "symlink", "symlink_directory", "aggregate", "condition"]
)
def test_snapshot_tampering_rejected_without_launch(manager, settings, damage):
    request = prepare_request(settings.data_root)
    input_root = Path(request.workspace) / "input"
    if damage == "checksum":
        (input_root / "model.fem").write_text("tampered")
    elif damage == "extra":
        (input_root / "rogue.py").write_text("print('not executed')")
    elif damage == "symlink":
        (input_root / "model.fem").unlink()
        (input_root / "model.fem").symlink_to("/etc/hosts")
    elif damage == "aggregate":
        request.snapshot_checksum = "0" * 64
    elif damage == "symlink_directory":
        (input_root / "linked-directory").symlink_to("/tmp", target_is_directory=True)
    elif damage == "condition":
        request.condition_code = "WRONG_CONDITION"
    with pytest.raises(AgentError) as failure:
        manager.submit(request)
    assert failure.value.code == "INPUT_INVALID"
    assert manager.registry.all() == []


def test_workspace_traversal_and_unknown_profile_rejected(manager, settings):
    request = prepare_request(settings.data_root)
    request.workspace = str(settings.data_root / "jobs" / ".." / request.job_no)
    with pytest.raises(AgentError, match="Workspace"):
        manager.submit(request)
    request.workspace = str(settings.data_root / "jobs" / request.job_no)
    request.solver_profile = "sh -c arbitrary"
    with pytest.raises(AgentError, match="profile"):
        manager.submit(request)
    assert manager.registry.all() == []


@pytest.mark.parametrize(
    "field,value",
    [("thicknessScale", -1), ("thicknessScale", "bad"), ("seed", []), ("subcase", False)],
)
def test_invalid_synthetic_parameters_rejected_before_launch(manager, settings, field, value):
    request = prepare_request(settings.data_root)
    workspace = Path(request.workspace)
    path = workspace / "input" / "manifest.json"
    manifest = json.loads(path.read_text())
    manifest["parameters"][field] = value
    atomic_json(path, manifest)
    files = {p.name: sha256_file(p) for p in (workspace / "input").iterdir()}
    request.snapshot_checksum = aggregate_checksum(files)
    atomic_json(
        workspace / "input-checksums.json", {"files": files, "checksum": request.snapshot_checksum}
    )
    with pytest.raises(AgentError) as error:
        manager.submit(request)
    assert error.value.code == "INPUT_INVALID"
    assert manager.registry.all() == []
