#!/usr/bin/env python3
"""M6 live restart, offline recovery, terminal reconciliation, and cancel race checks."""

import argparse
import base64
import http.client
import json
import os
import subprocess
import sys
import time
import urllib.error
import uuid

from agent_smoke import Api, assert_identity
from messaging_smoke import LocalServices
from scheduler_smoke import licenses, origin


def progress(message):
    print("[M6] " + message, file=sys.stderr, flush=True)


def until(probe, predicate, description, timeout=30):
    started = time.monotonic()
    next_update = started + 10
    while time.monotonic() - started < timeout:
        try:
            value = probe()
        except (
            urllib.error.URLError,
            TimeoutError,
            ConnectionError,
            http.client.HTTPException,
        ):
            value = None
        if value is not None and predicate(value):
            return value
        if time.monotonic() >= next_update:
            progress(
                f"Waiting for {description} ({round(time.monotonic() - started)} s)"
            )
            next_update = time.monotonic() + 10
        time.sleep(0.25)
    raise AssertionError(f"Timed out waiting for {description}")


def new_job(backend, runtime, mode="NONE"):
    number = backend.require(
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
                "failureMode": mode,
                "successProbability": 1,
                "progressInterval": 0.05,
                "seed": 37,
            },
        },
    )["jobNo"]
    job = backend.require("GET", "/api/v1/jobs/" + number)
    backend.require(
        "POST",
        f"/api/v1/jobs/{number}/submit",
        {"idempotencyKey": "m6-" + uuid.uuid4().hex},
    )
    return job


def execution_history(backend, job):
    return backend.require("GET", f"/api/v1/jobs/{job['jobNo']}/executions")


def running_execution(backend, workers, job):
    rows = until(
        lambda: execution_history(backend, job),
        lambda value: len(value) == 1 and value[0].get("externalExecutionId"),
        "durable Agent acceptance",
    )
    execution = rows[0]
    worker = workers[execution["nodeId"]]
    remote = until(
        lambda: worker.require(
            "GET", "/worker/v1/jobs/" + execution["externalExecutionId"]
        ),
        lambda value: value["state"] == "RUNNING" and value.get("processIdentity"),
        "independent running Solver process",
    )
    assert isinstance(remote["pid"], int) and remote["pid"] > 0
    assert remote["executionRequestId"] == execution["submitRequestId"], (
        "Worker observation belongs to a different execution request"
    )
    assert remote["externalExecutionId"] == execution["externalExecutionId"], (
        "Worker observation belongs to a different external execution"
    )
    until(
        lambda: backend.require("GET", "/api/v1/jobs/" + job["jobNo"]),
        lambda value: value["status"] == "RUNNING",
        "backend RUNNING observation",
    )
    return execution, worker, remote


def reservation(services, execution_id):
    rows = services.query(
        "SELECT JSON_OBJECT('executionId',execution_id,'status',status,'cpu',cpu,"
        "'memoryGb',memory_gb,'tokens',token_count,'feature',feature) "
        f"FROM cae_resource_reservation WHERE execution_id={int(execution_id)};"
    )
    assert len(rows) == 1
    return rows[0]


def parse_commands(services, job_id):
    return services.query(
        "SELECT JSON_OBJECT('eventId',event_id,'logicalEventId',logical_event_id,"
        "'status',status,'publishedAt',published_at) FROM cae_outbox_event "
        f"WHERE aggregate_id={int(job_id)} AND event_type='RESULT_PARSE_REQUESTED' "
        "AND exchange_name='cae.command' ORDER BY id;"
    )


def queue_message(broker, queue, event_id):
    messages = broker.require(
        "POST",
        "/api/queues/%2F/" + queue + "/get",
        {"count": 1000, "ackmode": "ack_requeue_true", "encoding": "auto"},
    )
    for message in messages:
        try:
            payload = json.loads(message["payload"])
        except (KeyError, ValueError):
            continue
        if payload.get("eventId") == event_id:
            return message
    return None


def callback_payload(remote):
    fields = (
        "executionRequestId",
        "externalExecutionId",
        "state",
        "progress",
        "pid",
        "processIdentity",
        "exitCode",
        "rawSolverCode",
        "rawMessage",
        "startedAt",
        "finishedAt",
    )
    return {key: remote[key] for key in fields if key in remote}


def completed_success(
    backend, internal, broker, services, job, execution, worker, identity
):
    remote = until(
        lambda: worker.require(
            "GET", "/worker/v1/jobs/" + identity["externalExecutionId"]
        ),
        lambda value: value["state"] == "SUCCESS",
        "successful independent Solver completion",
        timeout=75,
    )
    assert_identity(identity, remote)
    final = until(
        lambda: backend.require("GET", "/api/v1/jobs/" + job["jobNo"]),
        lambda value: value["status"] == "POST_PROCESSING",
        "automatic backend terminal reconciliation",
    )
    for _ in range(2):
        reply = internal.require(
            "POST",
            f"/internal/v1/agents/{execution['nodeId']}/executions/callback",
            callback_payload(remote),
        )
        assert reply["accepted"] is True
    rows = until(
        lambda: parse_commands(services, job["id"]),
        lambda value: len(value) == 1 and value[0]["status"] == "SENT",
        "unique confirmed parse command publication",
    )
    parse_message = until(
        lambda: queue_message(broker, "cae.job.parse", rows[0]["logicalEventId"]),
        lambda value: value is not None,
        "real Rabbit parse queue command",
    )
    assert parse_message["properties"]["delivery_mode"] == 2
    assert reservation(services, execution["id"])["status"] == "RELEASED"
    history = execution_history(backend, job)
    assert len(history) == 1 and history[0]["status"] == "SUCCESS"
    for field in ("submitRequestId", "externalExecutionId"):
        assert history[0][field] == execution[field]
    assert history[0]["processIdentity"] == identity["processIdentity"]
    assert history[0]["terminalAppliedAt"] and history[0]["resultParseRequestedAt"]
    events = backend.require("GET", f"/api/v1/jobs/{job['jobNo']}/events")
    assert sum(event["eventType"] == "RESULT_PARSE_REQUESTED" for event in events) == 1
    return {
        "jobNo": job["jobNo"],
        "executionId": execution["id"],
        "nodeId": execution["nodeId"],
        "requestId": execution["submitRequestId"],
        "externalExecutionId": remote["externalExecutionId"],
        "processIdentity": remote["processIdentity"],
        "backendState": final["status"],
        "workerState": remote["state"],
        "durableExecutions": 1,
        "parseCommandLogicalId": rows[0]["logicalEventId"],
        "parseCommands": 1,
        "parseQueueMessagePreserved": True,
        "resourcesReleased": True,
    }


def compose_read(services, arguments):
    process = subprocess.run(
        [*services.compose, *arguments],
        cwd=services.repo,
        env=services.environment,
        capture_output=True,
        text=True,
        timeout=40,
        check=False,
    )
    if process.returncode:
        raise RuntimeError(f"Compose service operation failed ({process.returncode})")
    return process.stdout.strip()


def restart_case(backend, internal, broker, services, workers, owned):
    progress("Backend restart scenario: starting an independent 40 s Solver")
    job = new_job(backend, runtime=40)
    owned.append(job)
    execution, worker, identity = running_execution(backend, workers, job)
    before = services.execution(execution["id"])
    node_services = ["worker-node-a", "worker-node-b", "worker-node-c"]
    worker_containers = compose_read(services, ["ps", "-q", *node_services])
    progress("Restarting only backend; worker containers and shared data stay running")
    compose_read(services, ["restart", "--no-deps", "backend"])
    during_restart = worker.require(
        "GET", "/worker/v1/jobs/" + identity["externalExecutionId"]
    )
    assert_identity(identity, during_restart)
    assert during_restart["state"] == "RUNNING", (
        "Restart stopped or outlived the running Solver"
    )
    until(
        lambda: backend.call("GET", "/actuator/health"),
        lambda value: value[0] == 200 and value[1]["status"] == "UP",
        "backend health after restart",
        timeout=50,
    )
    assert compose_read(services, ["ps", "-q", *node_services]) == worker_containers
    recovered = until(
        lambda: backend.require("GET", "/api/v1/jobs/" + job["jobNo"]),
        lambda value: value["status"] in {"RUNNING", "POST_PROCESSING"},
        "automatic recovery of the existing execution",
    )
    after = services.execution(execution["id"])
    for field in ("id", "requestId", "requestHash"):
        assert before[field] == after[field], (
            "Restart replaced the durable execution request"
        )
    result = completed_success(
        backend, internal, broker, services, job, execution, worker, identity
    )
    result.update(
        {
            "scenario": "backend-restart-during-solver",
            "workerContainersUnchanged": True,
            "workerStateDuringRestart": during_restart["state"],
            "recoveredState": recovered["status"],
        }
    )
    return result


def offline_case(backend, internal, broker, services, workers, owned, disabled):
    progress(
        "Offline scenario: stopping heartbeat while callbacks and the 60 s Solver continue"
    )
    job = new_job(backend, runtime=60)
    owned.append(job)
    execution, worker, identity = running_execution(backend, workers, job)
    held = reservation(services, execution["id"])
    assert held["status"] == "IN_USE"
    license_before = licenses(backend)
    worker.require("POST", "/worker/v1/faults/heartbeat", {"enabled": False})
    disabled.append(worker)
    node_id = execution["nodeId"]
    until(
        lambda: backend.require("GET", "/api/v1/compute-nodes"),
        lambda rows: any(
            row["nodeId"] == node_id and row["status"] == "OFFLINE" for row in rows
        ),
        "durable node OFFLINE after missing heartbeat",
        timeout=35,
    )
    unknown = until(
        lambda: backend.require("GET", "/api/v1/jobs/" + job["jobNo"]),
        lambda value: value["status"] == "UNKNOWN",
        "Job UNKNOWN without releasing capacity",
    )
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        observation = backend.require("GET", "/api/v1/jobs/" + job["jobNo"])
        assert observation["status"] == "UNKNOWN", (
            "A RUNNING callback falsely revived an offline Job"
        )
        assert reservation(services, execution["id"]) == held, (
            "Uncertain execution lost its reservation"
        )
        assert licenses(backend) == license_before, (
            "Offline observation released uncertain license capacity"
        )
        remote = worker.require(
            "GET", "/worker/v1/jobs/" + identity["externalExecutionId"]
        )
        assert remote["state"] == "RUNNING"
        assert_identity(identity, remote)
        callback = internal.require(
            "POST",
            f"/internal/v1/agents/{node_id}/executions/callback",
            callback_payload(remote),
        )
        assert callback["accepted"] is True and callback["jobStatus"] == "UNKNOWN", (
            "A verified RUNNING callback falsely revived the offline Job"
        )
        time.sleep(1)
    progress("Restoring heartbeat; reconciliation must recover the original process")
    worker.require("POST", "/worker/v1/faults/heartbeat", {"enabled": True})
    disabled.remove(worker)
    until(
        lambda: backend.require("GET", "/api/v1/jobs/" + job["jobNo"]),
        lambda value: value["status"] == "RUNNING",
        "UNKNOWN to RUNNING after heartbeat recovery",
        timeout=20,
    )
    recovered = worker.require(
        "GET", "/worker/v1/jobs/by-request/" + execution["submitRequestId"]
    )
    assert_identity(identity, recovered)
    result = completed_success(
        backend, internal, broker, services, job, execution, worker, identity
    )
    result.update(
        {
            "scenario": "heartbeat-offline-recovery",
            "offlineState": unknown["status"],
            "callbacksStayedEnabled": True,
            "capacityHeldWhileUnknown": True,
            "runningCallbackDidNotPromoteOfflineJob": True,
        }
    )
    return result


def failure_cases(backend, services, workers, owned):
    progress(
        "Runtime fault scenarios: remote failure and timeout must release confirmed capacity"
    )
    results = []
    for mode, code in (
        ("RUNTIME_FAILED", "SOLVER_RUNTIME_FAILED"),
        ("TIMEOUT", "SOLVER_TIMEOUT"),
    ):
        job = new_job(backend, runtime=3, mode=mode)
        owned.append(job)
        history = until(
            lambda: execution_history(backend, job),
            lambda rows: bool(rows),
            "fault execution allocation",
        )
        execution = history[0]
        worker = workers[execution["nodeId"]]
        remote = until(
            lambda: worker.call(
                "GET", "/worker/v1/jobs/by-request/" + execution["submitRequestId"]
            ),
            lambda reply: reply[0] == 200 and reply[1]["state"] == mode,
            "confirmed remote " + mode,
        )[1]
        final = until(
            lambda: backend.require("GET", "/api/v1/jobs/" + job["jobNo"]),
            lambda value: value["status"] == "RUNTIME_FAILED",
            "backend failure mapping and resource release",
        )
        assert final["failureCode"] == code
        assert final["rawSolverCode"] == remote["rawSolverCode"]
        assert final["rawMessage"] == remote["rawMessage"]
        assert reservation(services, execution["id"])["status"] == "RELEASED"
        assert parse_commands(services, job["id"]) == []
        history = execution_history(backend, job)
        assert len(history) == 1 and history[0]["status"] == mode
        assert history[0]["exitCode"] == remote["exitCode"]
        results.append(
            {
                "scenario": "runtime-" + mode.lower(),
                "jobNo": job["jobNo"],
                "executionId": execution["id"],
                "failureCode": code,
                "rawSolverCode": remote["rawSolverCode"],
                "workerState": remote["state"],
                "backendState": final["status"],
                "resourcesReleased": True,
                "parseCommands": 0,
            }
        )
    return results


def cancel_case(backend, internal, broker, services, workers, owned):
    progress(
        "Cancellation/completion race: terminal state must follow the actual remote outcome"
    )
    job = new_job(backend, runtime=2)
    owned.append(job)
    execution, worker, identity = running_execution(backend, workers, job)
    backend.require("POST", f"/api/v1/jobs/{job['jobNo']}/cancel", {})
    remote = until(
        lambda: worker.require(
            "GET", "/worker/v1/jobs/" + identity["externalExecutionId"]
        ),
        lambda value: value["state"] in {"CANCELLED", "SUCCESS"},
        "actual cancel/completion race outcome",
    )
    assert_identity(identity, remote)
    if remote["state"] == "SUCCESS":
        result = completed_success(
            backend, internal, broker, services, job, execution, worker, identity
        )
    else:
        final = until(
            lambda: backend.require("GET", "/api/v1/jobs/" + job["jobNo"]),
            lambda value: value["status"] == "CANCELLED",
            "confirmed backend cancellation",
        )
        assert reservation(services, execution["id"])["status"] == "RELEASED"
        assert parse_commands(services, job["id"]) == []
        assert len(execution_history(backend, job)) == 1
        repeated = backend.require("POST", f"/api/v1/jobs/{job['jobNo']}/cancel", {})
        assert (
            repeated["status"] == final["status"]
            and repeated["version"] == final["version"]
        )
        result = {
            "jobNo": job["jobNo"],
            "executionId": execution["id"],
            "processIdentity": remote["processIdentity"],
            "backendState": final["status"],
            "workerState": remote["state"],
            "resourcesReleased": True,
            "parseCommands": 0,
        }
    result["scenario"] = "cancel-completion-race"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument("--broker-url", default="http://127.0.0.1:15672")
    parser.add_argument(
        "--worker-urls",
        default="node-a=http://127.0.0.1:8001,node-b=http://127.0.0.1:8002,node-c=http://127.0.0.1:8003",
    )
    options = parser.parse_args()
    user, password = (
        os.getenv("CAE_API_USER", "engineer"),
        os.getenv("CAE_API_PASSWORD", "engineer123"),
    )
    auth = base64.b64encode(f"{user}:{password}".encode()).decode()
    token = os.getenv("SERVICE_TOKEN", "demo-service-token")
    backend = Api(origin(options.backend_url), {"Authorization": "Basic " + auth})
    internal = Api(origin(options.backend_url), {"X-Service-Token": token})
    services = LocalServices()
    broker = Api(origin(options.broker_url), services.rabbit_auth())
    workers = {}
    for entry in options.worker_urls.split(","):
        node_id, url = entry.split("=", 1)
        assert node_id not in workers
        workers[node_id] = Api(origin(url), {"X-Service-Token": token})
    assert set(workers) == {"node-a", "node-b", "node-c"}
    progress(
        "Waiting for old M4 runtime reservations to reconcile and all three nodes to be ONLINE"
    )
    baseline = until(
        lambda: licenses(backend),
        lambda snapshot: all(
            reserved == 0 and used == 0 for reserved, used in snapshot.values()
        ),
        "existing terminal runtime capacity release",
        timeout=35,
    )
    until(
        lambda: backend.require("GET", "/api/v1/compute-nodes"),
        lambda rows: all(
            any(row["nodeId"] == node_id and row["status"] == "ONLINE" for row in rows)
            for node_id in workers
        ),
        "three fresh heartbeat observations",
    )
    historic = json.loads(
        (services.repo / "docs/validation/m5-messaging-smoke.json").read_text()
    )["deadLetter"]["logicalEventId"]
    assert queue_message(broker, "cae.job.submit.dlq", historic), (
        "Historical M5 DLQ evidence disappeared"
    )
    assert broker.require("GET", "/api/queues/%2F/cae.job.parse")["consumers"] == 0, (
        "M6 smoke expects the M7 parse listener to be absent"
    )
    owned, disabled, results = [], [], []
    try:
        results.append(
            restart_case(backend, internal, broker, services, workers, owned)
        )
        results.append(
            offline_case(backend, internal, broker, services, workers, owned, disabled)
        )
        results.extend(failure_cases(backend, services, workers, owned))
        results.append(cancel_case(backend, internal, broker, services, workers, owned))
    finally:
        pending_failure = sys.exc_info()[0] is not None
        for worker in disabled:
            try:
                worker.require("POST", "/worker/v1/faults/heartbeat", {"enabled": True})
            except Exception:
                progress(
                    "Heartbeat fault restoration failed; restore the listed worker before continuing"
                )
                if not pending_failure:
                    raise
        for job in owned:
            try:
                current = backend.require("GET", "/api/v1/jobs/" + job["jobNo"])
                if current["status"] not in {
                    "POST_PROCESSING",
                    "RUNTIME_FAILED",
                    "CANCELLED",
                    "SUCCESS",
                }:
                    backend.require("POST", f"/api/v1/jobs/{job['jobNo']}/cancel", {})
            except Exception:
                progress(
                    "Owned smoke task cleanup requires inspection: " + job["jobNo"]
                )
                if not pending_failure:
                    raise
    until(
        lambda: licenses(backend),
        lambda value: value == baseline,
        "confirmed license ledger return to baseline",
    )
    assert queue_message(broker, "cae.job.submit.dlq", historic), (
        "A service restart removed historical DLQ evidence"
    )
    progress(
        "M6 reconciliation smoke completed; result commands remain durably queued for M7"
    )
    print(
        json.dumps(
            {
                "milestone": 6,
                "scenarios": results,
                "historicalDeadLetterPreserved": historic,
                "licenseLedgerReturnedToBaseline": True,
                "scope": "Actual restart and runtime reconciliation; parse commands queued, no parser/report completion claimed",
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
