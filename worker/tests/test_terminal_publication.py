import hashlib
import json
import signal
from pathlib import Path

import pytest
from conftest import prepare_request

from mock_solver import mock_solver


@pytest.mark.parametrize(
    "terminal,exit_code",
    [("SUCCESS", 0), ("RUNTIME_FAILED", 2), ("CANCELLED", 143), ("TIMEOUT", 124)],
)
def test_terminal_state_is_published_after_durable_closed_outputs(
    tmp_path, monkeypatch, terminal, exit_code
):
    request = prepare_request(
        tmp_path,
        runtimeSeconds=0.05,
        progressInterval=0.02,
        failureMode=terminal if terminal in {"RUNTIME_FAILED", "TIMEOUT"} else "NONE",
    )
    workspace = Path(request.workspace) / "executions" / request.execution_request_id
    workspace.mkdir(parents=True)
    if terminal == "CANCELLED":
        (workspace / "cancel.request").write_text("cancel\n", encoding="utf-8")
    config = request.model_dump(by_alias=True, mode="json")
    config.update(
        workspace=str(workspace),
        inputDirectory=str(Path(request.workspace) / "input"),
        externalExecutionId="node-a-terminal-publication",
        nodeId="node-a",
    )

    original_open = Path.open
    original_fsync = mock_solver.os.fsync
    original_atomic_json = mock_solver.atomic_json
    output_streams = {}
    synced_log_contents = []
    terminal_publications = []
    log_path = workspace / "solver.log"
    progress_path = workspace / "progress.jsonl"
    state_path = workspace / "state.json"

    def track_open(path, *args, **kwargs):
        stream = original_open(path, *args, **kwargs)
        mode = args[0] if args else kwargs.get("mode", "r")
        if path in (log_path, progress_path) and mode == "a":
            output_streams[path] = stream
        return stream

    def track_fsync(descriptor):
        original_fsync(descriptor)
        log_stream = output_streams.get(log_path)
        if log_stream is not None and not log_stream.closed and log_stream.fileno() == descriptor:
            synced_log_contents.append(log_path.read_text(encoding="utf-8"))

    def observe_publication(path, value):
        if path == state_path and value["state"] == terminal:
            footer = f"{terminal} exitCode={exit_code}"
            assert all(output_streams[path].closed for path in (log_path, progress_path))
            assert footer in log_path.read_text(encoding="utf-8")
            assert any(footer in contents for contents in synced_log_contents)
            terminal_publications.append(hashlib.sha256(log_path.read_bytes()).hexdigest())
        original_atomic_json(path, value)

    monkeypatch.setattr(Path, "open", track_open)
    monkeypatch.setattr(mock_solver.os, "fsync", track_fsync)
    monkeypatch.setattr(mock_solver, "atomic_json", observe_publication)
    previous_handlers = {
        number: signal.getsignal(number) for number in (signal.SIGTERM, signal.SIGINT)
    }
    try:
        assert mock_solver.run(config) == exit_code
    finally:
        for number, handler in previous_handlers.items():
            signal.signal(number, handler)

    assert terminal_publications == [hashlib.sha256(log_path.read_bytes()).hexdigest()]
    state = json.loads(state_path.read_text(encoding="utf-8"))
    assert state["state"] == terminal
    assert state["exitCode"] == exit_code
    assert state["executionRequestId"] == request.execution_request_id
    assert state["externalExecutionId"] == config["externalExecutionId"]
    assert state["finishedAt"]
    raw_path = workspace / "raw_result.json"
    if terminal == "SUCCESS":
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        assert raw["analysisType"] == request.analysis_type
        assert raw["conditionCode"] == request.condition_code
        assert raw["subcase"] == 1
        assert raw["mass_kg"] > 0
        assert len(raw["modes_hz"]) == 4
    else:
        assert not raw_path.exists()
