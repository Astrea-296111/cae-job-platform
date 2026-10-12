#!/usr/bin/env python3
"""Twelve actual Compose scenarios, durable batch backpressure and synthetic reports."""

import concurrent.futures
import json
import os
import subprocess
import sys
import time
import uuid
from agent_smoke import Api, assert_identity
from messaging_smoke import LocalServices, command_evidence
from parser_smoke import publish_duplicate
from reconciliation_smoke import until, running_execution, reservation
from report_smoke import user, validate_report, success, identity


def progress(message):
    print("[M10] " + message, file=sys.stderr, flush=True)


class Acceptance:
    def __init__(self):
        self.api = user("admin", "admin123")
        self.services = LocalServices()
        self.broker = Api("http://127.0.0.1:15672", self.services.rabbit_auth())
        headers = {"X-Service-Token": os.getenv("SERVICE_TOKEN", "demo-service-token")}
        self.workers = {
            "node-" + node: Api("http://127.0.0.1:" + str(8001 + i), headers)
            for i, node in enumerate("abc")
        }
        self.owned = []
        self.disabled = []
        self.original_nodes = self.api.require("GET", "/api/v1/compute-nodes")
        self.results = []
        self.catalog = self.api.require("GET", "/api/v1/cases")

    def entry(self, case):
        return next(
            row for row in self.catalog if row["casePath"] == "structure/" + case
        )

    def body(self, case="case-current", runtime=3, mode="NONE", delay=0):
        entry = self.entry(case)
        return {
            "projectId": 1,
            "modelRevisionId": 1 if entry["modelRevision"] == "V36" else 2,
            "analysisType": "STRUCTURE",
            "conditionCode": entry["conditionCode"],
            "casePath": entry["casePath"],
            "resourceProfile": "SMALL",
            "mock": {
                "runtimeSeconds": runtime,
                "failureMode": mode,
                "responseDelayOnceSeconds": delay,
                "progressInterval": 0.1,
                "successProbability": 1,
            },
        }

    def create(self, **options):
        row = self.api.require("POST", "/api/v1/jobs", self.body(**options))
        job = self.api.require("GET", "/api/v1/jobs/" + row["jobNo"])
        self.owned.append(job)
        return job

    def submit(self, job, key=None):
        return self.api.require(
            "POST",
            "/api/v1/jobs/" + job["jobNo"] + "/submit",
            {"idempotencyKey": key or "m10-" + uuid.uuid4().hex},
        )

    def get(self, job):
        return self.api.require("GET", "/api/v1/jobs/" + job["jobNo"])

    def terminal(self, job, state):
        return until(
            lambda: self.get(job), lambda row: row["status"] == state, state, 120
        )

    def history(self, job):
        return self.api.require("GET", "/api/v1/jobs/" + job["jobNo"] + "/executions")

    def record(self, number, name, **evidence):
        self.results.append(
            {"number": number, "scenario": name, "passed": True, **evidence}
        )
        progress(str(number) + " " + name + " PASS")
        from pathlib import Path

        path = Path(".local/acceptance/m10-checkpoint.json")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps({"results": self.results}, ensure_ascii=False, indent=2)
        )

    def drain(self, drained):
        for node in self.original_nodes:
            self.api.require(
                "PATCH",
                "/api/v1/compute-nodes/" + node["nodeId"] + "/drain",
                {"drained": drained if drained is not None else node["drained"]},
            )

    def run(self):
        assert all(
            node["cpuReserved"] == node["cpuUsed"] == 0 for node in self.original_nodes
        ), "Use an idle public demo environment"
        baseline_dlq = {
            name: self.broker.require("GET", "/api/queues/%2F/" + name)["messages"]
            for name in [
                "cae.job.submit.dlq",
                "cae.job.parse.dlq",
                "cae.job.postprocess.dlq",
                "cae.job.report.dlq",
            ]
        }
        try:
            job = self.create()
            self.submit(job)
            success(self.api, job)
            _, report = validate_report(self.api, job)
            execution, _ = identity(self.api, self.workers, job)
            events = self.api.require("GET", "/api/v1/jobs/" + job["jobNo"] + "/events")
            observed = {row["toStatus"] for row in events}
            assert {
                "VALIDATING",
                "READY",
                "QUEUED",
                "SUBMITTING",
                "SUBMITTED",
                "RUNNING",
                "POST_PROCESSING",
                "REPORTING",
                "SUCCESS",
            } <= observed
            self.record(
                1, "structure-success", executionId=execution["id"], report=report
            )

            bad = self.create(case="case-missing-include")
            self.submit(bad)
            failed = self.terminal(bad, "VALIDATION_FAILED")
            assert not self.history(bad)
            self.record(
                2,
                "missing-include-no-solver",
                jobNo=bad["jobNo"],
                failureCode=failed["failureCode"],
            )

            duplicate = self.create(runtime=5)
            key = "m10-duplicate-" + uuid.uuid4().hex
            with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
                list(pool.map(lambda _: self.submit(duplicate, key), range(5)))
            success(self.api, duplicate)
            one, remote = identity(self.api, self.workers, duplicate)
            self.record(
                3,
                "duplicate-submit-one-process",
                jobNo=duplicate["jobNo"],
                executionId=one["id"],
                pid=remote["pid"],
            )

            delayed = self.create(runtime=12, delay=8)
            self.submit(delayed)
            retried = until(
                lambda: command_evidence(self.services, delayed["id"]),
                lambda evidence: any(
                    row["eventType"] == "EXECUTION_SUBMIT_REQUESTED"
                    for row in evidence["outbox"]
                )
                and all(row["status"] == "DONE" for row in evidence["inbox"]),
                "delayed accepted response resolved with the original execution",
                30,
            )
            # M6 callbacks may confirm execution before the HTTP timeout is handled.
            # Replay the actual original Rabbit command regardless of which confirmation wins.
            envelope = self.services.query(
                "SELECT JSON_OBJECT('payload',payload) FROM cae_outbox_event WHERE aggregate_id="
                + str(delayed["id"])
                + " AND event_type='EXECUTION_SUBMIT_REQUESTED' ORDER BY id LIMIT 1;"
            )[0]["payload"]
            envelope = json.loads(envelope) if isinstance(envelope, str) else envelope
            envelope["deliveryId"] = "m10-replay-" + uuid.uuid4().hex
            before_ack = (
                self.broker.require("GET", "/api/queues/%2F/cae.job.submit")
                .get("message_stats", {})
                .get("ack", 0)
            )
            replay = self.broker.require(
                "POST",
                "/api/exchanges/%2F/cae.command/publish",
                {
                    "properties": {
                        "delivery_mode": 2,
                        "message_id": envelope["deliveryId"],
                        "content_type": "application/json",
                    },
                    "routing_key": "job.submit",
                    "payload": json.dumps(envelope),
                    "payload_encoding": "string",
                },
            )
            assert replay["routed"]
            until(
                lambda: self.broker.require("GET", "/api/queues/%2F/cae.job.submit")
                .get("message_stats", {})
                .get("ack", 0),
                lambda count: count > before_ack,
                "actual replay ACK",
                30,
            )
            success(self.api, delayed)
            one, remote = identity(self.api, self.workers, delayed)
            self.record(
                4,
                "accepted-response-timeout-one-process",
                jobNo=delayed["jobNo"],
                executionId=one["id"],
                pid=remote["pid"],
                deliveryEvidence=retried,
                responseDelayOnceSeconds=8,
                originalCommandReplayAcknowledged=True,
            )

            runtime = self.create(mode="RUNTIME_FAILED")
            self.submit(runtime)
            failed = self.terminal(runtime, "RUNTIME_FAILED")
            attempt = self.history(runtime)[0]
            logs = self.api.require("GET", "/api/v1/jobs/" + runtime["jobNo"] + "/logs")
            assert logs["lines"] and failed["failureCode"] and attempt["rawSolverCode"]
            assert reservation(self.services, attempt["id"])["status"] == "RELEASED"
            self.record(
                5,
                "runtime-failure-with-log",
                jobNo=runtime["jobNo"],
                failureCode=failed["failureCode"],
                rawSolverCode=attempt["rawSolverCode"],
            )

            self.drain(True)
            waiting = self.create()
            self.submit(waiting)
            self.terminal(waiting, "WAITING_RESOURCE")
            assert not self.history(waiting)
            self.drain(None)
            success(self.api, waiting)
            self.record(6, "resource-wait-auto-resume", jobNo=waiting["jobNo"])

            offline = self.create(runtime=50)
            self.submit(offline)
            one, worker, remote = running_execution(self.api, self.workers, offline)
            held = reservation(self.services, one["id"])
            worker.require("POST", "/worker/v1/faults/heartbeat", {"enabled": False})
            self.disabled.append(worker)
            self.terminal(offline, "UNKNOWN")
            assert reservation(self.services, one["id"]) == held
            observed = worker.require(
                "GET", "/worker/v1/jobs/" + remote["externalExecutionId"]
            )
            assert observed["state"] == "RUNNING"
            assert_identity(remote, observed)
            worker.require("POST", "/worker/v1/faults/heartbeat", {"enabled": True})
            self.disabled.remove(worker)
            success(self.api, offline, 120)
            _, recovered = identity(self.api, self.workers, offline)
            assert_identity(remote, recovered)
            self.record(
                7,
                "offline-unknown-preserves-process-and-capacity",
                jobNo=offline["jobNo"],
                executionId=one["id"],
                pid=remote["pid"],
            )

            cancel = self.create(runtime=10)
            self.submit(cancel)
            one, worker, remote = running_execution(self.api, self.workers, cancel)
            self.api.require("POST", "/api/v1/jobs/" + cancel["jobNo"] + "/cancel")
            final = until(
                lambda: self.get(cancel),
                lambda row: row["status"] in {"CANCELLED", "SUCCESS"},
                "confirmed cancel race",
                45,
            )
            assert reservation(self.services, one["id"])["status"] == "RELEASED"
            assert len(self.history(cancel)) == 1
            self.record(
                8,
                "cancel-race-confirmed-release",
                jobNo=cancel["jobNo"],
                status=final["status"],
                executionId=one["id"],
            )

            commands = self.services.query(
                "SELECT JSON_OBJECT('envelope',payload,'logicalEventId',logical_event_id) FROM cae_outbox_event WHERE aggregate_id="
                + str(job["id"])
                + " AND event_type='RESULT_PARSE_REQUESTED' ORDER BY id LIMIT 1;"
            )
            original = commands[0]["envelope"]
            original = json.loads(original) if isinstance(original, str) else original
            parser_before = self.api.require(
                "GET", "/api/v1/jobs/" + job["jobNo"] + "/parses"
            )
            metric_before = self.api.require(
                "GET", "/api/v1/jobs/" + job["jobNo"] + "/metrics"
            )
            ids = [commands[0]["logicalEventId"], "m10-parse-" + uuid.uuid4().hex]
            for logical in ids:
                publish_duplicate(self.broker, original, logical)
            fresh = ids[1]
            until(
                lambda: self.services.query(
                    "SELECT JSON_OBJECT('status',status) FROM cae_message_inbox WHERE consumer_name='result-parse-v1' AND logical_event_id='"
                    + fresh
                    + "';"
                ),
                lambda rows: rows and rows[0]["status"] == "DONE",
                "duplicate parser ACK",
                30,
            )
            assert (
                self.api.require("GET", "/api/v1/jobs/" + job["jobNo"] + "/parses")
                == parser_before
            )
            assert (
                self.api.require("GET", "/api/v1/jobs/" + job["jobNo"] + "/metrics")
                == metric_before
            )
            self.record(
                9,
                "duplicate-parse-unique-records",
                jobNo=job["jobNo"],
                metricCount=len(metric_before),
            )

            children = [
                self.body(
                    case="case-modal" if i % 7 == 0 else "case-current",
                    runtime=8,
                    mode="RUNTIME_FAILED" if i in {5, 17} else "NONE",
                )
                for i in range(40)
            ]
            children[9] = self.body(case="case-missing-include", runtime=18)
            batch = self.api.require(
                "POST",
                "/api/v1/batches",
                {
                    "name": "M10 real partial failure batch",
                    "projectId": 1,
                    "idempotencyKey": "m10-batch-" + uuid.uuid4().hex,
                    "jobs": children,
                },
            )
            assert batch["createdChildren"] == 0 and batch["counts"]["waiting"] == 40
            snapshots = []
            deadline = time.monotonic() + 360
            next_note = time.monotonic()
            while time.monotonic() < deadline:
                batch = self.api.require("GET", "/api/v1/batches/" + batch["batchNo"])
                counts = batch["counts"]
                assert (
                    sum(
                        counts[key]
                        for key in [
                            "waiting",
                            "running",
                            "success",
                            "failed",
                            "cancelled",
                        ]
                    )
                    == 40
                )
                assert (
                    batch["backpressure"]["activeJobs"]
                    <= batch["backpressure"]["activeLimit"]
                )
                snapshots.append(
                    {
                        "created": batch["createdChildren"],
                        "paused": batch["backpressure"]["paused"],
                        "counts": counts,
                    }
                )
                if time.monotonic() >= next_note:
                    progress("batch " + json.dumps(counts))
                    next_note = time.monotonic() + 10
                if batch["progress"] == 100:
                    break
                time.sleep(1)
            assert (
                batch["status"] == "PARTIAL_FAILED"
                and batch["counts"]["success"] == 37
                and batch["counts"]["failed"] == 3
            ), batch
            assert any(row["paused"] for row in snapshots), (
                "Active backpressure was never exercised"
            )
            assigned = {row["nodeId"] for row in batch["children"] if row["nodeId"]}
            assert len(assigned) == 3, assigned
            for child in batch["children"]:
                target = self.api.require("GET", "/api/v1/jobs/" + child["jobNo"])
                self.owned.append(target)
                if child["status"] == "SUCCESS":
                    validate_report(self.api, target)
            self.record(
                10,
                "forty-child-partial-failure-batch",
                batchNo=batch["batchNo"],
                counts=batch["counts"],
                nodes=sorted(assigned),
                pausedSamples=sum(row["paused"] for row in snapshots),
                snapshots=snapshots,
            )

            modal = self.create(case="case-modal")
            self.submit(modal)
            success(self.api, modal)
            _, modal_report = validate_report(self.api, modal)
            assert (
                modal_report["conditionCode"] == "BIW_MODAL"
                and modal_report["subcase"] == 2
            )
            self.record(
                11,
                "report-kpi-image-condition-subcase-match",
                torsionReport=report,
                modalReport=modal_report,
            )

            restart = self.create(runtime=25)
            self.submit(restart)
            one, worker, remote = running_execution(self.api, self.workers, restart)
            before = subprocess.run(
                [
                    *self.services.compose,
                    "ps",
                    "-q",
                    "worker-node-a",
                    "worker-node-b",
                    "worker-node-c",
                ],
                cwd=self.services.repo,
                env=self.services.environment,
                capture_output=True,
                text=True,
                check=True,
            ).stdout
            subprocess.run(
                [*self.services.compose, "restart", "--no-deps", "backend"],
                cwd=self.services.repo,
                env=self.services.environment,
                capture_output=True,
                check=True,
                timeout=50,
            )
            during = worker.require(
                "GET", "/worker/v1/jobs/" + remote["externalExecutionId"]
            )
            assert_identity(remote, during)
            assert during["state"] == "RUNNING"
            until(
                lambda: self.api.call("GET", "/actuator/health"),
                lambda row: row[0] == 200 and row[1]["status"] == "UP",
                "backend restart health",
                60,
            )
            success(self.api, restart, 120)
            _, after = identity(self.api, self.workers, restart)
            assert_identity(remote, after)
            actual = subprocess.run(
                [
                    *self.services.compose,
                    "ps",
                    "-q",
                    "worker-node-a",
                    "worker-node-b",
                    "worker-node-c",
                ],
                cwd=self.services.repo,
                env=self.services.environment,
                capture_output=True,
                text=True,
                check=True,
            ).stdout
            assert actual == before
            self.record(
                12,
                "backend-restart-solver-continues",
                jobNo=restart["jobNo"],
                executionId=one["id"],
                pid=remote["pid"],
                workerContainersUnchanged=True,
            )
            for name, count in baseline_dlq.items():
                assert (
                    self.broker.require("GET", "/api/queues/%2F/" + name)["messages"]
                    >= count
                )
            until(
                lambda: self.api.require("GET", "/api/v1/compute-nodes"),
                lambda nodes: all(
                    row["cpuReserved"]
                    == row["cpuUsed"]
                    == row["memoryReservedGb"]
                    == row["memoryUsedGb"]
                    == 0
                    for row in nodes
                ),
                "final resource ledger zero",
                30,
            )
            assert len(self.results) == 12
            return {
                "scenario": "M10 twelve real E2E scenarios",
                "results": self.results,
                "dlqEvidencePreserved": True,
                "finalNodeLedger": self.api.require("GET", "/api/v1/compute-nodes"),
            }
        finally:
            for worker in self.disabled:
                worker.require("POST", "/worker/v1/faults/heartbeat", {"enabled": True})
            self.drain(None)
            for job in self.owned:
                current = self.get(job)
                if current["status"] in {
                    "CREATED",
                    "READY",
                    "QUEUED",
                    "WAITING_RESOURCE",
                    "SUBMITTING",
                    "SUBMITTED",
                    "RUNNING",
                    "UNKNOWN",
                    "CANCEL_REQUESTED",
                }:
                    self.api.require("POST", "/api/v1/jobs/" + job["jobNo"] + "/cancel")


if __name__ == "__main__":
    print(json.dumps(Acceptance().run(), ensure_ascii=False, indent=2))
