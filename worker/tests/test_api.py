import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import httpx
import pytest
from conftest import prepare_request, wait_terminal
from fastapi.testclient import TestClient

from agents.execution_manager import ExecutionManager
from app.main import create_app

HEADERS = {"X-Service-Token": "demo-service-token"}


def test_service_token_submit_query_cancel_and_fault_control(settings):
    request = prepare_request(settings.data_root, runtimeSeconds=1)
    with TestClient(create_app(settings)) as client:
        assert client.get("/worker/v1/node/status").status_code == 401
        assert (
            client.post(
                "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True)
            ).status_code
            == 401
        )
        accepted = client.post(
            "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), headers=HEADERS
        )
        assert accepted.status_code == 202
        external_id = accepted.json()["externalExecutionId"]
        assert accepted.json()["accepted"]
        queried = client.get(
            f"/worker/v1/jobs/by-request/{request.execution_request_id}", headers=HEADERS
        )
        assert queried.json()["externalExecutionId"] == external_id
        assert client.post(
            "/worker/v1/faults/heartbeat", json={"enabled": False}, headers=HEADERS
        ).json() == {"enabled": False}
        assert client.app.state.heartbeat_control["enabled"] is False
        assert (
            client.post(f"/worker/v1/jobs/{external_id}/cancel", headers=HEADERS).status_code == 200
        )
        assert wait_terminal(client.app.state.manager, external_id)["state"] == "CANCELLED"


def test_real_http_response_timeout_retry_has_one_registry_and_process(settings):
    request = prepare_request(settings.data_root, runtimeSeconds=0.8, responseDelayOnceSeconds=0.7)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    environment = {
        **os.environ,
        "DATA_ROOT": str(settings.data_root),
        "REGISTRY_PATH": str(settings.registry_path),
        "BACKEND_URL": "",
        "NODE_ID": "node-a",
        "SERVICE_TOKEN": "demo-service-token",
    }
    with (settings.data_root / "http-agent.log").open("w") as log:
        server = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "app.main:app",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
            ],
            cwd=Path(__file__).resolve().parents[1],
            env=environment,
            stdout=log,
            stderr=log,
        )
    external_id = None
    try:
        base = f"http://127.0.0.1:{port}"
        with httpx.Client(base_url=base, headers=HEADERS, trust_env=False) as client:
            started = time.monotonic()
            while True:
                try:
                    if client.get("/health").status_code == 200:
                        break
                except httpx.ConnectError:
                    pass
                assert time.monotonic() - started < 10
                time.sleep(0.05)
            with pytest.raises(httpx.ReadTimeout):
                client.post(
                    "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), timeout=0.12
                )
            recovered = client.get(f"/worker/v1/jobs/by-request/{request.execution_request_id}")
            assert recovered.status_code == 200
            external_id = recovered.json()["externalExecutionId"]
            original_pid = recovered.json()["pid"]
            retry = client.post(
                "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), timeout=1
            )
            assert retry.status_code == 202
            assert retry.json()["externalExecutionId"] == external_id
            manager = ExecutionManager(settings)
            assert len(manager.registry.all()) == 1
            assert manager.query(external_id)["pid"] == original_pid
            terminal = wait_terminal(manager, external_id)
            assert terminal["state"] == "SUCCESS"
            assert (
                Path(terminal["workspace"], "solver.log").read_text().count("Mock Solver RUNNING")
                == 1
            )
    finally:
        if external_id:
            manager = ExecutionManager(settings)
            manager.cancel(external_id)
        server.terminate()
        server.wait(timeout=10)
