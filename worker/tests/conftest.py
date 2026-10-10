import time
from pathlib import Path

import pytest

from agents.execution_manager import ExecutionManager
from agents.files import aggregate_checksum, atomic_json, sha256_file
from agents.process_registry import TERMINAL
from app.contracts import SubmitRequest
from app.settings import Settings


def prepare_request(root: Path, number=1, analysis="STRUCTURE", **mock):
    job_no = f"CAE-20261010-{number:06d}"
    workspace = root / "jobs" / job_no
    inputs = workspace / "input"
    inputs.mkdir(parents=True, exist_ok=True)
    condition = "BIW_TORSION" if analysis == "STRUCTURE" else f"{analysis}_STANDARD"
    manifest = {
        "caseId": f"TEST-{number}",
        "analysisType": analysis,
        "conditionCode": condition,
        "entryDeck": "model.fem",
        "includes": [],
        "parameters": {"seed": 42, "thicknessScale": 1.0, "subcase": 1},
    }
    atomic_json(inputs / "manifest.json", manifest)
    (inputs / "model.fem").write_text("$ Synthetic deck\n")
    files = {path.name: sha256_file(path) for path in inputs.iterdir()}
    checksum = aggregate_checksum(files)
    atomic_json(workspace / "input-checksums.json", {"files": files, "checksum": checksum})
    return SubmitRequest.model_validate(
        {
            "executionRequestId": f"request-{number}",
            "jobNo": job_no,
            "workspace": str(workspace),
            "solverProfile": f"{analysis}_STANDARD",
            "analysisType": analysis,
            "conditionCode": condition,
            "snapshotChecksum": checksum,
            "resource": {"cpu": 4, "memoryGb": 4, "walltimeMin": 1},
            "mock": {"runtimeSeconds": 0.15, "progressInterval": 0.03, **mock},
        }
    )


def wait_terminal(manager, external_id, timeout=10):
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        state = manager.query(external_id)
        if state["state"] in TERMINAL:
            return state
        time.sleep(0.03)
    raise AssertionError(f"Execution did not terminate: {manager.query(external_id)}")


@pytest.fixture
def settings(tmp_path):
    return Settings(
        data_root=tmp_path,
        registry_path=tmp_path / "agents" / "node-a" / "registry.sqlite3",
        solver_tags=("STRUCTURE", "NVH", "CRASH", "CFD"),
    )


@pytest.fixture
def manager(settings):
    manager = ExecutionManager(settings)
    yield manager
    for row in manager.registry.all():
        if manager.query(row["external_id"])["state"] not in TERMINAL:
            manager.cancel(row["external_id"])
            wait_terminal(manager, row["external_id"])
