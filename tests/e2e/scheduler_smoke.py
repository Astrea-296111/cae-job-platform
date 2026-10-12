#!/usr/bin/env python3
"""Milestone 4 live scheduling, durable execution identity, and confirmed cancellation."""

import argparse
import base64
import concurrent.futures
import json
import os
import urllib.parse
import uuid

from agent_smoke import Api, assert_identity, terminal, wait_for


def origin(value):
    parsed = urllib.parse.urlsplit(value)
    assert parsed.scheme in {"http", "https"} and parsed.hostname, (
        "Use an HTTP(S) origin"
    )
    assert not parsed.username and not parsed.password, "Use environment credentials"
    assert parsed.path in {"", "/"} and not parsed.query and not parsed.fragment
    return value.rstrip("/")


def licenses(backend):
    return {
        row["feature"]: (row["reservedTokens"], row["inUseTokens"])
        for row in backend.require("GET", "/api/v1/license-pools")
    }


def create_job(backend, runtime):
    return backend.require(
        "POST",
        "/api/v1/jobs",
        {
            "projectId": 1,
            "modelRevisionId": 2,
            "analysisType": "STRUCTURE",
            "conditionCode": "BIW_TORSION",
            "casePath": "structure/case-good",
            "resourceProfile": "SMALL",
            "mock": {
                "runtimeSeconds": runtime,
                "successProbability": 1,
                "failureMode": "NONE",
                "progressInterval": 0.05,
                "seed": 37,
            },
        },
    )["jobNo"]


def accepted_execution(backend, job_no):
    job = wait_for(
        lambda: backend.require("GET", "/api/v1/jobs/" + job_no),
        lambda body: body["status"] == "SUBMITTED",
        "backend durable Agent acceptance",
        timeout=40,
    )
    history = backend.require("GET", f"/api/v1/jobs/{job_no}/executions")
    assert len(history) == 1, "A scheduling command created multiple attempts"
    execution = history[0]
    assert job["currentExecutionId"] == execution["id"], (
        "Current attempt association differs"
    )
    assert execution["externalExecutionId"], "Agent acceptance was not persisted"
    assert "request" not in execution, (
        "Execution history leaked the internal submission body"
    )
    return execution


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument(
        "--worker-urls",
        default="node-a=http://127.0.0.1:8001,node-b=http://127.0.0.1:8002,node-c=http://127.0.0.1:8003",
    )
    options = parser.parse_args()
    user = os.getenv("CAE_API_USER", "engineer")
    password = os.getenv("CAE_API_PASSWORD", "engineer123")
    basic = base64.b64encode(f"{user}:{password}".encode()).decode()
    backend = Api(origin(options.backend_url), {"Authorization": "Basic " + basic})
    token = os.getenv("SERVICE_TOKEN", "demo-service-token")
    workers = {}
    for value in options.worker_urls.split(","):
        node_id, url = value.split("=", 1)
        assert node_id not in workers, "Repeated worker node ID"
        workers[node_id] = Api(origin(url), {"X-Service-Token": token})
    assert set(workers) == {"node-a", "node-b", "node-c"}, "Verify all three demo nodes"

    ready_nodes = wait_for(
        lambda: backend.require("GET", "/api/v1/compute-nodes"),
        lambda rows: all(
            any(
                row["nodeId"] == node_id
                and row["status"] == "ONLINE"
                and row["workspaceReachable"] is True
                and row["drained"] is False
                for row in rows
            )
            for node_id in workers
        ),
        "three healthy reachable compute nodes",
        timeout=35,
    )

    normal_no = create_job(backend, runtime=4)
    key = "m4-normal-" + uuid.uuid4().hex
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as callers:
        responses = list(
            callers.map(
                lambda _: backend.call(
                    "POST", f"/api/v1/jobs/{normal_no}/submit", {"idempotencyKey": key}
                ),
                range(8),
            )
        )
    assert all(status == 202 for status, _ in responses), (
        "Duplicate command was rejected"
    )
    normal_execution = accepted_execution(backend, normal_no)
    normal_worker = workers[normal_execution["nodeId"]]
    query_path = "/worker/v1/jobs/" + normal_execution["externalExecutionId"]
    identity = normal_worker.require("GET", query_path)
    assert isinstance(identity.get("pid"), int) and identity["pid"] > 0, (
        "No real solver PID"
    )
    completed = terminal(normal_worker, normal_execution["externalExecutionId"])
    assert completed["state"] == "SUCCESS", (
        "Synthetic solver did not complete successfully"
    )
    assert_identity(identity, completed)
    by_request = normal_worker.require(
        "GET", "/worker/v1/jobs/by-request/" + normal_execution["submitRequestId"]
    )
    assert_identity(completed, by_request)
    normal_after = backend.require("GET", "/api/v1/jobs/" + normal_no)
    assert normal_after["status"] == "SUBMITTED", (
        "M4 does not install the M6 runtime monitor"
    )
    assert len(backend.require("GET", f"/api/v1/jobs/{normal_no}/executions")) == 1

    license_baseline = licenses(backend)
    node_baselines = {
        row["nodeId"]: row for row in backend.require("GET", "/api/v1/compute-nodes")
    }
    cancel_no = create_job(backend, runtime=20)
    backend.require(
        "POST",
        f"/api/v1/jobs/{cancel_no}/submit",
        {"idempotencyKey": "m4-cancel-" + uuid.uuid4().hex},
    )
    cancel_execution = accepted_execution(backend, cancel_no)
    cancel_worker = workers[cancel_execution["nodeId"]]
    running = wait_for(
        lambda: cancel_worker.require(
            "GET", "/worker/v1/jobs/" + cancel_execution["externalExecutionId"]
        ),
        lambda body: body["state"] == "RUNNING",
        "running solver before cancellation",
    )
    allocated = licenses(backend)
    assert allocated["OPTISTRUCT"][1] == license_baseline["OPTISTRUCT"][1] + 1
    backend.require("POST", f"/api/v1/jobs/{cancel_no}/cancel", {})
    cancelled_job = wait_for(
        lambda: backend.require("GET", "/api/v1/jobs/" + cancel_no),
        lambda body: body["status"] == "CANCELLED",
        "backend confirmed remote cancellation",
        timeout=30,
    )
    cancelled_remote = terminal(cancel_worker, cancel_execution["externalExecutionId"])
    assert cancelled_remote["state"] == "CANCELLED", (
        "Backend cancellation was not remote-confirmed"
    )
    assert_identity(running, cancelled_remote)
    cancelled_history = backend.require("GET", f"/api/v1/jobs/{cancel_no}/executions")
    assert len(cancelled_history) == 1 and cancelled_history[0]["status"] == "CANCELLED"
    assert cancelled_history[0]["cancelRequested"] is True, (
        "Cancellation intent was not durable"
    )
    wait_for(
        lambda: licenses(backend),
        lambda snapshot: snapshot == license_baseline,
        "license reservation release after confirmation",
    )
    baseline_node = node_baselines[cancel_execution["nodeId"]]
    fields = ("cpuUsed", "cpuReserved", "memoryUsedGb", "memoryReservedGb")
    wait_for(
        lambda: backend.require(
            "GET", "/api/v1/compute-nodes/" + cancel_execution["nodeId"]
        ),
        lambda node: all(node[field] == baseline_node[field] for field in fields),
        "node resource release and heartbeat cache refresh",
    )
    before_repeat = backend.require("GET", f"/api/v1/jobs/{cancel_no}/events")
    repeated = backend.require("POST", f"/api/v1/jobs/{cancel_no}/cancel", {})
    assert repeated["status"] == "CANCELLED"
    assert repeated["version"] == cancelled_job["version"], (
        "Repeated cancel rewrote the Job"
    )
    assert backend.require("GET", f"/api/v1/jobs/{cancel_no}/events") == before_repeat

    print(
        json.dumps(
            {
                "milestone": 4,
                "onlineNodes": [
                    row["nodeId"] for row in ready_nodes if row["status"] == "ONLINE"
                ],
                "normal": {
                    "jobNo": normal_no,
                    "nodeId": normal_execution["nodeId"],
                    "executionId": normal_execution["id"],
                    "externalExecutionId": normal_execution["externalExecutionId"],
                    "processIdentity": completed["processIdentity"],
                    "duplicateCommands": 8,
                    "durableExecutions": 1,
                    "workerState": "SUCCESS",
                    "backendState": normal_after["status"],
                },
                "cancel": {
                    "jobNo": cancel_no,
                    "nodeId": cancel_execution["nodeId"],
                    "executionId": cancel_execution["id"],
                    "externalExecutionId": cancel_execution["externalExecutionId"],
                    "processIdentity": cancelled_remote["processIdentity"],
                    "workerState": cancelled_remote["state"],
                    "backendState": cancelled_job["status"],
                    "licenseReleased": True,
                    "nodeResourcesReleased": True,
                },
                "scope": "M4 scheduler and cancellation; successful runtime awaits the M6 monitor",
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
