#!/usr/bin/env python3
"""M5 live RabbitMQ/Outbox/Inbox checks; runtime completion awaits the M6 monitor."""

import argparse
import base64
import concurrent.futures
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

from agent_smoke import Api, assert_identity, terminal, wait_for
from scheduler_smoke import licenses, origin


class LocalServices:
    """Read local demo bindings in subprocess memory; never print credential values."""

    def __init__(self):
        self.repo = Path(__file__).resolve().parents[2]
        self.environment = dict(os.environ)
        self.environment.setdefault("DOCKER_CONFIG", str(self.repo / ".local/docker"))
        Path(self.environment["DOCKER_CONFIG"]).mkdir(parents=True, exist_ok=True)
        self.compose = ["docker", "compose", "-f", "deploy/docker-compose.yml"]

    def container(self, service, command, stdin=None):
        process = subprocess.run(
            [*self.compose, "exec", "-T", service, "sh", "-c", command],
            input=stdin,
            capture_output=True,
            text=True,
            cwd=self.repo,
            env=self.environment,
            timeout=10,
            check=False,
        )
        if process.returncode:
            raise RuntimeError(
                f"Local {service} evidence query failed ({process.returncode})"
            )
        return process.stdout

    def rabbit_auth(self):
        values = self.container(
            "rabbitmq",
            'test -n "$RABBITMQ_DEFAULT_USER" && test -n "$RABBITMQ_DEFAULT_PASS" '
            '&& printf "%s\\n%s" "$RABBITMQ_DEFAULT_USER" "$RABBITMQ_DEFAULT_PASS"',
        )
        username, password = values.split("\n", 1)
        encoded = base64.b64encode(f"{username}:{password}".encode()).decode()
        return {"Authorization": "Basic " + encoded}

    def query(self, sql):
        assert sql.lstrip().upper().startswith("SELECT "), (
            "Evidence queries must be read-only"
        )
        rows = self.container(
            "mysql",
            'test -n "$MYSQL_USER" && test -n "$MYSQL_PASSWORD" && '
            'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" '
            '--database="$MYSQL_DATABASE" --protocol=socket --batch --raw --skip-column-names',
            sql,
        )
        return [json.loads(line) for line in rows.splitlines() if line]

    def outbox(self, job_id):
        job_id = int(job_id)
        assert job_id > 0
        return self.query(
            "SELECT JSON_OBJECT('eventId',event_id,'logicalEventId',logical_event_id,"
            "'eventType',event_type,'status',status,'retryCount',retry_count,"
            "'deliveryAttempt',delivery_attempt,'routingKey',routing_key,"
            "'publishedAt',published_at) FROM cae_outbox_event "
            f"WHERE aggregate_id={job_id} ORDER BY id;"
        )

    def inbox(self, logical_ids):
        assert logical_ids
        assert all(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value) for value in logical_ids)
        ids = ",".join("'" + value + "'" for value in logical_ids)
        return self.query(
            "SELECT JSON_OBJECT('logicalEventId',logical_event_id,'consumerName',consumer_name,"
            "'status',status,'retryCount',retry_count,'lastDeliveryId',last_delivery_id) "
            "FROM cae_message_inbox WHERE consumer_name='job-submit-v1' "
            f"AND logical_event_id IN ({ids});"
        )

    def execution(self, execution_id):
        execution_id = int(execution_id)
        assert execution_id > 0
        rows = self.query(
            "SELECT JSON_OBJECT('id',id,'jobId',job_id,'requestId',submit_request_id,"
            "'requestHash',SHA2(CAST(request_json AS CHAR),256),'status',status,"
            "'externalExecutionId',external_execution_id) FROM cae_job_execution "
            f"WHERE id={execution_id};"
        )
        assert len(rows) == 1, "Durable execution row is absent"
        return rows[0]


def command_evidence(services, job_id):
    rows = services.outbox(job_id)
    commands = [
        row
        for row in rows
        if row["eventType"] in {"JOB_SUBMIT_REQUESTED", "EXECUTION_SUBMIT_REQUESTED"}
        and row["routingKey"] == "job.submit"
    ]
    ids = {row["logicalEventId"] for row in commands}
    inbox = services.inbox(ids) if ids else []
    return {"outbox": commands, "inbox": inbox}


def completed_delivery(evidence):
    rows = evidence["outbox"]
    inbox = evidence["inbox"]
    required = {"JOB_SUBMIT_REQUESTED", "EXECUTION_SUBMIT_REQUESTED"}
    return (
        required <= {row["eventType"] for row in rows}
        and all(row["status"] == "SENT" and row["publishedAt"] for row in rows)
        and {row["logicalEventId"] for row in rows}
        == {row["logicalEventId"] for row in inbox}
        and all(row["status"] == "DONE" for row in inbox)
    )


def new_job(backend, response_delay=0):
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
                "runtimeSeconds": 12,
                "successProbability": 1,
                "failureMode": "NONE",
                "progressInterval": 0.05,
                "responseDelayOnceSeconds": response_delay,
                "seed": 37,
            },
        },
    )["jobNo"]
    return backend.require("GET", "/api/v1/jobs/" + number)


def first_execution(backend, job):
    rows = wait_for(
        lambda: backend.require("GET", f"/api/v1/jobs/{job['jobNo']}/executions"),
        lambda history: bool(history),
        "Rabbit handler's allocated execution",
        timeout=30,
    )
    assert len(rows) == 1, "A submission command allocated multiple executions"
    return rows[0]


def remote_by_request(worker, execution):
    return wait_for(
        lambda: worker.call(
            "GET", "/worker/v1/jobs/by-request/" + execution["submitRequestId"]
        ),
        lambda reply: reply[0] == 200 and reply[1].get("processIdentity"),
        "accepted process before HTTP response acknowledgement",
    )[1]


def verify_dead_letter(broker, backend, services, job):
    event_id = "m5-invalid-" + uuid.uuid4().hex
    delivery_id = str(uuid.uuid4())
    envelope = {
        "eventId": event_id,
        "deliveryId": delivery_id,
        "eventType": "JOB_SUBMIT_REQUESTED",
        "jobId": job["id"],
        "jobNo": job["jobNo"],
        "occurredAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "payloadVersion": 999,
        "deliveryAttempt": 0,
        "payload": {},
    }
    history_before = backend.require("GET", f"/api/v1/jobs/{job['jobNo']}/executions")
    published = broker.require(
        "POST",
        "/api/exchanges/%2F/cae.command/publish",
        {
            "properties": {
                "delivery_mode": 2,
                "message_id": delivery_id,
                "content_type": "application/json",
            },
            "routing_key": "job.submit",
            "payload": json.dumps(envelope),
            "payload_encoding": "string",
        },
    )
    assert published.get("routed") is True, "Targeted malformed command was not routed"

    def peek():
        messages = broker.require(
            "POST",
            "/api/queues/%2F/cae.job.submit.dlq/get",
            {"count": 1000, "ackmode": "ack_requeue_true", "encoding": "auto"},
        )
        for message in messages:
            try:
                body = json.loads(message["payload"])
            except (ValueError, KeyError):
                continue
            if body.get("eventId") == event_id:
                return message
        return None

    dead = wait_for(
        peek, lambda message: message is not None, "targeted command DLQ", timeout=20
    )
    deaths = dead.get("properties", {}).get("headers", {}).get("x-death", [])
    assert any(
        death.get("queue") == "cae.job.submit" and death.get("reason") == "rejected"
        for death in deaths
    ), "DLQ payload lacks broker dead-letter evidence"
    assert services.inbox({event_id}) == [], (
        "Malformed envelope reached a business Inbox claim"
    )
    assert (
        backend.require("GET", f"/api/v1/jobs/{job['jobNo']}/executions")
        == history_before
    )
    return {
        "logicalEventId": event_id,
        "deliveryId": delivery_id,
        "queue": "cae.job.submit.dlq",
        "reason": "rejected",
        "messagePreserved": True,
        "businessExecutionUnchanged": True,
    }


def release_owned_jobs(backend, jobs):
    results = []
    for job in jobs:
        backend.require("POST", f"/api/v1/jobs/{job['jobNo']}/cancel", {})
        final = wait_for(
            lambda job=job: backend.require("GET", "/api/v1/jobs/" + job["jobNo"]),
            lambda body: body["status"] in {"CANCELLED", "POST_PROCESSING", "SUCCESS"},
            "owned smoke attempt's remote terminal confirmation",
            timeout=25,
        )
        results.append({"jobNo": job["jobNo"], "status": final["status"]})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument("--broker-url", default="http://127.0.0.1:15672")
    parser.add_argument(
        "--worker-urls",
        default="node-a=http://127.0.0.1:8001,node-b=http://127.0.0.1:8002,node-c=http://127.0.0.1:8003",
    )
    options = parser.parse_args()
    user = os.getenv("CAE_API_USER", "engineer")
    password = os.getenv("CAE_API_PASSWORD", "engineer123")
    basic = base64.b64encode(f"{user}:{password}".encode()).decode()
    backend = Api(origin(options.backend_url), {"Authorization": "Basic " + basic})
    services = LocalServices()
    broker = Api(origin(options.broker_url), services.rabbit_auth())
    workers = {}
    token = os.getenv("SERVICE_TOKEN", "demo-service-token")
    for value in options.worker_urls.split(","):
        node_id, url = value.split("=", 1)
        assert node_id not in workers
        workers[node_id] = Api(origin(url), {"X-Service-Token": token})
    assert set(workers) == {"node-a", "node-b", "node-c"}
    queue = wait_for(
        lambda: broker.require("GET", "/api/queues/%2F/cae.job.submit"),
        lambda value: value.get("consumers", 0) > 0,
        "real Rabbit submission listener",
    )
    assert queue["durable"] is True
    assert queue["arguments"].get("x-dead-letter-exchange") == "cae.dead"
    acknowledgements_before = queue.get("message_stats", {}).get("ack", 0)
    for name in (
        "cae.job.submit",
        "cae.job.parse",
        "cae.job.postprocess",
        "cae.job.report",
    ):
        for suffix in ("", ".dlq"):
            assert (
                broker.require("GET", "/api/queues/%2F/" + name + suffix)["durable"]
                is True
            )
    license_baseline = licenses(backend)
    owned_jobs = []
    summaries = []
    cleanup = []
    try:
        normal = new_job(backend)
        owned_jobs.append(normal)
        key = "m5-normal-" + uuid.uuid4().hex
        started = time.monotonic()
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as callers:
            replies = list(
                callers.map(
                    lambda _: backend.call(
                        "POST",
                        f"/api/v1/jobs/{normal['jobNo']}/submit",
                        {"idempotencyKey": key},
                    ),
                    range(8),
                )
            )
        assert all(status == 202 for status, _ in replies)
        execution = first_execution(backend, normal)
        worker = workers[execution["nodeId"]]
        identity = remote_by_request(worker, execution)
        evidence = wait_for(
            lambda: command_evidence(services, normal["id"]),
            completed_delivery,
            "confirmed Outbox publication and Inbox completion",
        )
        ack_seconds = time.monotonic() - started
        current = worker.require(
            "GET", "/worker/v1/jobs/" + identity["externalExecutionId"]
        )
        assert current["state"] == "RUNNING", "Consumer waited for Solver completion"
        assert_identity(identity, current)
        assert ack_seconds < 10, (
            "Durable command handling exceeded the short acceptance window"
        )
        wait_for(
            lambda: broker.require("GET", "/api/queues/%2F/cae.job.submit"),
            lambda value: value.get("messages_unacknowledged", 0) == 0
            and value.get("message_stats", {}).get("ack", 0) > acknowledgements_before,
            "manual broker acknowledgement before Solver completion",
        )
        assert (
            worker.require("GET", "/worker/v1/jobs/" + identity["externalExecutionId"])[
                "state"
            ]
            == "RUNNING"
        ), "Broker ACK arrived only after Solver completion"
        finished = terminal(worker, identity["externalExecutionId"])
        assert finished["state"] == "SUCCESS"
        assert_identity(identity, finished)
        assert (
            len(backend.require("GET", f"/api/v1/jobs/{normal['jobNo']}/executions"))
            == 1
        )
        assert (
            backend.require("GET", "/api/v1/jobs/" + normal["jobNo"])["status"]
            == "SUBMITTED"
        )
        summaries.append(
            {
                "scenario": "real-broker-duplicate-command",
                "jobNo": normal["jobNo"],
                "executionId": execution["id"],
                "externalExecutionId": identity["externalExecutionId"],
                "processIdentity": identity["processIdentity"],
                "duplicateCommands": 8,
                "durableExecutions": 1,
                "inboxDoneWhileWorkerRunning": True,
                "commandToInboxDoneSeconds": round(ack_seconds, 3),
                "deliveryEvidence": evidence,
            }
        )

        delayed = new_job(backend, response_delay=4)
        owned_jobs.append(delayed)
        backend.require(
            "POST",
            f"/api/v1/jobs/{delayed['jobNo']}/submit",
            {"idempotencyKey": "m5-timeout-" + uuid.uuid4().hex},
        )
        delayed_execution = first_execution(backend, delayed)
        delayed_worker = workers[delayed_execution["nodeId"]]
        before_retry = remote_by_request(delayed_worker, delayed_execution)
        durable_before = services.execution(delayed_execution["id"])
        timeout_evidence = wait_for(
            lambda: command_evidence(services, delayed["id"]),
            lambda rows: completed_delivery(rows)
            and any(row["deliveryAttempt"] > 0 for row in rows["outbox"])
            and any(row["retryCount"] > 0 for row in rows["inbox"]),
            "accepted-response timeout's durable retry delivery",
            timeout=25,
        )
        durable_after = services.execution(delayed_execution["id"])
        for field in ("id", "requestId", "requestHash"):
            assert durable_before[field] == durable_after[field], (
                "Retry changed the stored attempt"
            )
        after_retry = delayed_worker.require(
            "GET", "/worker/v1/jobs/by-request/" + delayed_execution["submitRequestId"]
        )
        assert_identity(before_retry, after_retry)
        assert (
            durable_after["externalExecutionId"] == before_retry["externalExecutionId"]
        )
        assert (
            len(backend.require("GET", f"/api/v1/jobs/{delayed['jobNo']}/executions"))
            == 1
        )
        events = backend.require("GET", f"/api/v1/jobs/{delayed['jobNo']}/events")
        assert any(
            event["eventType"] == "AGENT_SUBMIT_UNCERTAIN"
            and event["detail"].get("failureCode") == "AGENT_TIMEOUT"
            for event in events
        ), "The injected response delay did not produce a real control-plane timeout"
        completed = terminal(delayed_worker, before_retry["externalExecutionId"])
        assert completed["state"] == "SUCCESS"
        assert_identity(before_retry, completed)
        summaries.append(
            {
                "scenario": "accepted-response-timeout-redelivery",
                "jobNo": delayed["jobNo"],
                "executionId": delayed_execution["id"],
                "externalExecutionId": before_retry["externalExecutionId"],
                "processIdentity": before_retry["processIdentity"],
                "immutableRequestHash": durable_after["requestHash"],
                "durableExecutions": 1,
                "deliveryEvidence": timeout_evidence,
            }
        )
        dead_letter = verify_dead_letter(broker, backend, services, normal)
    finally:
        pending_failure = sys.exc_info()[0] is not None
        try:
            cleanup = release_owned_jobs(backend, owned_jobs)
        except Exception:
            if not pending_failure:
                raise
            print(
                "Owned smoke cancellation remains unconfirmed; inspect the listed Job IDs.",
                file=sys.stderr,
            )
            print(json.dumps([job["jobNo"] for job in owned_jobs]), file=sys.stderr)
    wait_for(
        lambda: licenses(backend),
        lambda value: value == license_baseline,
        "owned smoke jobs' confirmed resource release",
    )
    print(
        json.dumps(
            {
                "milestone": 5,
                "broker": "RabbitMQ 3.13",
                "scenarios": summaries,
                "deadLetter": dead_letter,
                "cleanup": cleanup,
                "licenseLedgerReturnedToBaseline": True,
                "scope": "Real broker delivery and idempotent handling; no full result/report pipeline claimed",
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
