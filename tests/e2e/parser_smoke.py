#!/usr/bin/env python3
"""M7 live parsing, immutable artifact downloads, duplicate delivery and version checks."""

import argparse
import base64
import concurrent.futures
import csv
import datetime
import hashlib
import http.client
import io
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

from agent_smoke import Api, assert_identity
from messaging_smoke import LocalServices
from reconciliation_smoke import queue_message, reservation
from scheduler_smoke import licenses, origin


def progress(message):
    print("[M7] " + message, file=sys.stderr, flush=True)


def node_ledger(rows):
    return {
        row["nodeId"]: tuple(
            row[field]
            for field in ("cpuUsed", "cpuReserved", "memoryUsedGb", "memoryReservedGb")
        )
        for row in rows
    }


def fresh_nodes(rows, expected):
    now = datetime.datetime.now(datetime.timezone.utc)
    return all(
        any(
            row["nodeId"] == node_id
            and row["status"] == "ONLINE"
            and row["workspaceReachable"] is True
            and row["drained"] is False
            and row["lastHeartbeat"]
            and (
                now
                - datetime.datetime.fromisoformat(
                    row["lastHeartbeat"].replace("Z", "+00:00")
                )
            ).total_seconds()
            < 10
            for row in rows
        )
        for node_id in expected
    )


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


def metrics(backend, job, version):
    return backend.require(
        "GET", f"/api/v1/jobs/{job['jobNo']}/metrics?parserVersion={version}"
    )


def records(backend, job):
    return backend.require("GET", f"/api/v1/jobs/{job['jobNo']}/parses")


def artifacts(backend, job):
    return backend.require("GET", f"/api/v1/jobs/{job['jobNo']}/artifacts")


def download(backend, artifact, job=None):
    artifact_id = int(artifact["id"])
    path = (
        f"/api/v1/artifacts/{artifact_id}/download"
        if job is None
        else f"/api/v1/jobs/{job['jobNo']}/artifacts/{artifact_id}/download"
    )
    request = urllib.request.Request(backend.origin + path, headers=backend.headers)
    with backend.opener.open(request, timeout=10) as response:
        assert response.status == 200
        assert (
            response.headers.get_content_type()
            == artifact["contentType"].split(";", 1)[0]
        )
        data = response.read()
    assert len(data) == artifact["sizeBytes"]
    assert hashlib.sha256(data).hexdigest() == artifact["checksum"], (
        "Downloaded artifact does not match its durable checksum"
    )
    return data


def commands(services, job, event_type):
    assert event_type in {"RESULT_PARSE_REQUESTED", "POST_PROCESS_REQUESTED"}
    return services.query(
        "SELECT JSON_OBJECT('eventId',event_id,'logicalEventId',logical_event_id,"
        "'status',status,'envelope',JSON_EXTRACT(payload,'$')) FROM cae_outbox_event "
        f"WHERE aggregate_id={int(job['id'])} AND event_type='{event_type}' "
        "AND exchange_name='cae.command' ORDER BY id;"
    )


def version_command(services, job, event_type, version):
    rows = commands(services, job, event_type)
    return [
        row
        for row in rows
        if row["envelope"]["payload"].get("parserVersion", "v1") == version
    ]


def complete_record(backend, job, version):
    rows = until(
        lambda: records(backend, job),
        lambda rows: any(
            row["parserVersion"] == version and row["status"] == "COMPLETE"
            for row in rows
        ),
        f"{job['jobNo']} parser {version} completion",
        timeout=45,
    )
    selected = [row for row in rows if row["parserVersion"] == version]
    assert len(selected) == 1
    return selected[0]


def expected_metrics(raw):
    analysis = raw["analysisType"]
    if analysis == "STRUCTURE":
        result = {
            "MASS": (raw["mass_kg"], "kg", "MASS"),
            "TORSIONAL_STIFFNESS": (
                raw["torsional_stiffness_nm_per_deg"],
                "N*m/deg",
                "TORSIONAL_STIFFNESS",
            ),
            "BENDING_STIFFNESS": (
                raw["bending_stiffness_n_per_mm"],
                "N/mm",
                "BENDING_STIFFNESS",
            ),
        }
        assert len(raw["modes_hz"]) == 4
        result.update(
            {
                f"MODE_{i + 1}_FREQUENCY": (value, "Hz", "FREQUENCY")
                for i, value in enumerate(raw["modes_hz"])
            }
        )
        return result
    if analysis == "NVH":
        return {
            "NVH_PEAK_FREQUENCY": (raw["peak_frequency_hz"], "Hz", "FREQUENCY"),
            "NVH_PEAK_RESPONSE": (
                raw["peak_response_db"],
                "dB",
                "SOUND_PRESSURE_LEVEL",
            ),
        }
    if analysis == "CRASH":
        return {
            "CRASH_INTRUSION_MAX": (raw["max_intrusion_mm"], "mm", "LENGTH"),
            "CRASH_PEAK_ACCELERATION": (
                raw["peak_acceleration_g"],
                "g",
                "ACCELERATION",
            ),
            "CRASH_ENERGY_BALANCE_RATIO": (raw["energy_balance_ratio"], "1", "RATIO"),
        }
    assert analysis == "CFD"
    assert isinstance(raw["converged"], bool)
    return {
        "CFD_CD": (raw["cd"], "1", "COEFFICIENT"),
        "CFD_CL": (raw["cl"], "1", "COEFFICIENT"),
        "CFD_RESIDUAL_FINAL": (raw["residual_final"], "1", "RESIDUAL"),
        "CFD_CONVERGED": (str(raw["converged"]).lower(), "1", "BOOLEAN"),
    }


def assert_canonical(rows, raw, record):
    expected = expected_metrics(raw)
    indexed = {row["metricCode"]: row for row in rows}
    assert len(rows) == len(expected) and set(indexed) == set(expected)
    assert record["metricCount"] == len(expected)
    assert record["maturity"] == (
        "COMPLETE" if raw["analysisType"] == "STRUCTURE" else "PILOT"
    )
    for code, (value, unit, dimension) in expected.items():
        row = indexed[code]
        assert row["unit"] == unit and row["dimension"] == dimension
        assert row["conditionCode"] == raw["conditionCode"] == record["conditionCode"]
        assert row["subcase"] == raw["subcase"] == record["subcase"]
        assert row["parserVersion"] == record["parserVersion"]
        assert row["parseRecordId"] == record["id"]
        assert row["executionId"] == record["executionId"]
        if isinstance(value, str):
            assert row["valueNum"] is None and row["valueText"] == value
        else:
            assert row["valueText"] is None
            assert math.isfinite(row["valueNum"])
            assert math.isclose(row["valueNum"], value, rel_tol=0, abs_tol=1e-9)
        expected_status = (
            "REVIEW_REQUIRED"
            if code == "CFD_CONVERGED" and value == "false"
            else "VALID"
        )
        assert row["status"] == expected_status


def assert_exported(rows, raw):
    expected = expected_metrics(raw)
    indexed = {row["metricCode"]: row for row in rows}
    assert len(rows) == len(expected) and set(indexed) == set(expected)
    for code, (value, unit, dimension) in expected.items():
        row = indexed[code]
        assert row["conditionCode"] == raw["conditionCode"]
        assert int(row["subcase"]) == raw["subcase"]
        assert row["unit"] == unit and row["dimension"] == dimension
        if isinstance(value, str):
            assert row["valueNum"] in (None, "") and row["valueText"] == value
        else:
            assert row["valueText"] in (None, "")
            assert math.isfinite(float(row["valueNum"]))
            assert math.isclose(float(row["valueNum"]), value, rel_tol=0, abs_tol=1e-9)
        assert row["status"] == (
            "REVIEW_REQUIRED"
            if code == "CFD_CONVERGED" and value == "false"
            else "VALID"
        )


def verify_version(backend, services, broker, job, version, raw, execution):
    record = complete_record(backend, job, version)
    assert reservation(services, execution["id"])["status"] == "RELEASED"
    assert record["executionId"] == execution["id"]
    assert record["analysisType"] == job["analysisType"]
    rows = metrics(backend, job, version)
    assert_canonical(rows, raw, record)
    files = [row for row in artifacts(backend, job) if row["parserVersion"] == version]
    assert {row["artifactType"] for row in files} == {"RESULT_JSON", "RESULT_CSV"}
    assert len(files) == 2
    for artifact in files:
        assert artifact["parseRecordId"] == record["id"]
        assert artifact["executionId"] == execution["id"]
        assert artifact["metadata"]["conditionCode"] == raw["conditionCode"]
        assert artifact["metadata"]["subcase"] == raw["subcase"]
        assert artifact["metadata"]["parserVersion"] == version
        assert artifact["metadata"]["maturity"] == record["maturity"]
        assert (
            artifact["metadata"]["executionRequestId"] == execution["submitRequestId"]
        )
        data = download(backend, artifact)
        assert download(backend, artifact, job) == data
        if artifact["artifactType"] == "RESULT_JSON":
            document = json.loads(data)
            assert document["schemaVersion"] == (1 if version == "v1" else 2)
            assert document["jobNo"] == job["jobNo"]
            assert document["executionId"] == execution["id"]
            assert document["executionRequestId"] == execution["submitRequestId"]
            assert document["analysisType"] == raw["analysisType"]
            assert document["rawChecksum"] == record["rawChecksum"]
            assert document["parserVersion"] == version
            assert document["maturity"] == record["maturity"]
            assert document["conditionCode"] == raw["conditionCode"]
            assert document["subcase"] == raw["subcase"]
            assert_exported(document["metrics"], raw)
            if version == "v2":
                assert (
                    document["provenance"]["snapshotChecksum"] == job["inputChecksum"]
                )
                assert document["provenance"]["solverProfile"] == job["solverProfile"]
                assert (
                    document["provenance"]["qualityPolicy"] == "physics-consistency-v2"
                )
        else:
            exported = list(csv.DictReader(io.StringIO(data.decode("utf-8"))))
            assert all(row["parserVersion"] == version for row in exported)
            assert_exported(exported, raw)
    command_rows = until(
        lambda: version_command(services, job, "POST_PROCESS_REQUESTED", version),
        lambda rows: len(rows) == 1 and rows[0]["status"] == "SENT",
        f"{job['jobNo']} unique {version} post command publication",
    )
    command = command_rows[0]
    payload = command["envelope"]["payload"]
    assert payload["jobNo"] == job["jobNo"]
    assert payload["analysisType"] == raw["analysisType"]
    assert payload["parseRecordId"] == record["id"]
    assert payload["executionId"] == execution["id"]
    assert payload["executionRequestId"] == execution["submitRequestId"]
    assert payload["parserVersion"] == version
    assert payload["rawChecksum"] == record["rawChecksum"]
    assert payload["conditionCode"] == raw["conditionCode"]
    assert payload["subcase"] == raw["subcase"]
    assert payload["postVersion"] == 1
    message = until(
        lambda: queue_message(broker, "cae.job.postprocess", command["logicalEventId"]),
        lambda value: value is not None,
        "persistent post queue message",
    )
    assert message["properties"]["delivery_mode"] == 2
    return {
        "record": record,
        "metrics": rows,
        "artifacts": files,
        "postCommandLogicalId": command["logicalEventId"],
    }


def execution_identity(backend, workers, job):
    history = backend.require("GET", f"/api/v1/jobs/{job['jobNo']}/executions")
    assert len(history) == 1 and history[0]["status"] == "SUCCESS"
    execution = history[0]
    remote = workers[execution["nodeId"]].require(
        "GET", "/worker/v1/jobs/by-request/" + execution["submitRequestId"]
    )
    assert remote["state"] == "SUCCESS"
    assert remote["executionRequestId"] == execution["submitRequestId"]
    assert remote["externalExecutionId"] == execution["externalExecutionId"]
    logs = [
        row for row in artifacts(backend, job) if row["artifactType"] == "SOLVER_LOG"
    ]
    assert len(logs) == 1 and logs[0]["parserVersion"] == ""
    log_data = download(backend, logs[0])
    assert job["jobNo"].encode() in log_data
    assert remote["processIdentity"].encode() in log_data
    return execution, remote


def create_case(backend, services, entry, owned):
    revision = entry["modelRevision"]
    project_id, revision_id = (
        (1, {"V36": 1, "V37": 2}[revision])
        if (entry["analysisType"] == "STRUCTURE")
        else (2, 3)
    )
    result = backend.require(
        "POST",
        "/api/v1/jobs",
        {
            "projectId": project_id,
            "modelRevisionId": revision_id,
            "analysisType": entry["analysisType"],
            "conditionCode": entry["conditionCode"],
            "casePath": entry["casePath"],
            "resourceProfile": "SMALL",
            "mock": {
                "runtimeSeconds": 2,
                "failureMode": "NONE",
                "successProbability": 1,
                "progressInterval": 0.05,
                "seed": 37,
            },
        },
    )
    job = backend.require("GET", f"/api/v1/jobs/{result['jobNo']}")
    owned.append(job)
    backend.require(
        "POST",
        f"/api/v1/jobs/{job['jobNo']}/submit",
        {"idempotencyKey": "m7-" + uuid.uuid4().hex},
    )
    complete_record(backend, job, "v1")
    job = backend.require("GET", f"/api/v1/jobs/{job['jobNo']}")
    assert job["status"] == "POST_PROCESSING"
    manifest = json.loads(
        (services.repo / "demo-data" / entry["casePath"] / "manifest.json").read_text()
    )
    raw_artifacts = [
        row for row in artifacts(backend, job) if row["artifactType"] == "RAW_RESULT"
    ]
    assert len(raw_artifacts) == 1 and raw_artifacts[0]["parserVersion"] == ""
    raw = json.loads(download(backend, raw_artifacts[0]))
    assert raw["analysisType"] == entry["analysisType"]
    assert raw["conditionCode"] == entry["conditionCode"]
    assert raw["subcase"] == manifest["parameters"]["subcase"]
    if "MODAL" in entry["conditionCode"]:
        assert raw["conditionCode"] == "BIW_MODAL" and raw["subcase"] == 2
    return job, raw, raw_artifacts[0]


def publish_duplicate(broker, original, logical_id):
    envelope = dict(original)
    envelope.update(eventId=logical_id, deliveryId="m7-delivery-" + uuid.uuid4().hex)
    response = broker.require(
        "POST",
        "/api/exchanges/%2F/cae.command/publish",
        {
            "properties": {
                "delivery_mode": 2,
                "message_id": envelope["deliveryId"],
                "content_type": "application/json",
            },
            "routing_key": "job.parse",
            "payload": json.dumps(envelope),
            "payload_encoding": "string",
        },
    )
    assert response["routed"] is True
    return envelope


def duplicate_case(
    backend, services, broker, workers, job, raw, execution, identity, before
):
    progress(
        "Publishing actual Rabbit duplicate parse commands with reused and new logical IDs"
    )
    command = version_command(services, job, "RESULT_PARSE_REQUESTED", "v1")
    assert len(command) == 1
    logical_ids = [command[0]["logicalEventId"], "m7-repeat-" + uuid.uuid4().hex]
    assert all(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", value) for value in logical_ids
    )
    ack_before = (
        broker.require("GET", "/api/queues/%2F/cae.job.parse")
        .get("message_stats", {})
        .get("ack", 0)
    )
    deliveries = [
        publish_duplicate(broker, command[0]["envelope"], key) for key in logical_ids
    ]
    assert deliveries[0]["deliveryId"] != command[0]["eventId"]

    def inbox():
        ids = ",".join("'" + value + "'" for value in logical_ids)
        return services.query(
            "SELECT JSON_OBJECT('logicalEventId',logical_event_id,'status',status,"
            "'consumerName',consumer_name) FROM cae_message_inbox "
            f"WHERE consumer_name='result-parse-v1' AND logical_event_id IN ({ids});"
        )

    inbox_rows = until(
        inbox,
        lambda rows: len(rows) == 2 and all(row["status"] == "DONE" for row in rows),
        "actual parse duplicate logical Inbox completion",
    )
    ack_after = until(
        lambda: broker.require("GET", "/api/queues/%2F/cae.job.parse")
        .get("message_stats", {})
        .get("ack", 0),
        lambda total: total >= ack_before + 2,
        "actual broker ACK counters for two duplicate deliveries",
    )
    after = verify_version(backend, services, broker, job, "v1", raw, execution)
    assert after == before, (
        "Duplicate parse changed canonical records or immutable artifact metadata"
    )
    assert len(records(backend, job)) == 1
    _, remote = execution_identity(backend, workers, job)
    assert_identity(identity, remote)
    return {
        "logicalEventIds": logical_ids,
        "physicalDeliveryIds": [row["deliveryId"] for row in deliveries],
        "inbox": inbox_rows,
        "brokerAckDelta": ack_after - ack_before,
        "parseRecords": 1,
        "metrics": len(after["metrics"]),
        "versionedArtifacts": 2,
        "executionCount": 1,
    }


def reparse_case(
    backend, services, broker, workers, job, raw, execution, identity, before
):
    progress(
        "Submitting six concurrent parser-v2 requests while preserving v1 artifacts and execution"
    )
    old_contents = {row["id"]: download(backend, row) for row in before["artifacts"]}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        futures = [
            pool.submit(
                backend.require,
                "POST",
                f"/api/v1/jobs/{job['jobNo']}/reparse",
                {"parserVersion": "v2"},
            )
            for _ in range(6)
        ]
        replies = [future.result() for future in futures]
    assert len({row["id"] for row in replies}) == 1
    after = verify_version(backend, services, broker, job, "v2", raw, execution)
    assert after["record"]["id"] == replies[0]["id"]
    assert after["record"]["id"] != before["record"]["id"]
    assert after["record"]["rawChecksum"] == before["record"]["rawChecksum"]
    assert {row["parserVersion"] for row in records(backend, job)} == {"v1", "v2"}
    assert len(records(backend, job)) == 2
    assert (
        verify_version(backend, services, broker, job, "v1", raw, execution) == before
    )
    for artifact in before["artifacts"]:
        assert download(backend, artifact) == old_contents[artifact["id"]]
    assert {row["uri"] for row in before["artifacts"]}.isdisjoint(
        {row["uri"] for row in after["artifacts"]}
    )
    _, remote = execution_identity(backend, workers, job)
    assert_identity(identity, remote)
    return {
        "concurrentRequests": 6,
        "v1ParseRecordId": before["record"]["id"],
        "v2ParseRecordId": after["record"]["id"],
        "v1ArtifactsUnchanged": True,
        "v2Artifacts": [
            {key: row[key] for key in ("id", "uri", "checksum")}
            for row in after["artifacts"]
        ],
        "sameRawChecksum": True,
        "executionCount": 1,
        "postCommandLogicalId": after["postCommandLogicalId"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument("--broker-url", default="http://127.0.0.1:15672")
    parser.add_argument(
        "--worker-urls",
        default="node-a=http://127.0.0.1:8001,node-b=http://127.0.0.1:8002,node-c=http://127.0.0.1:8003",
    )
    options = parser.parse_args()
    credentials = f"{os.getenv('CAE_API_USER', 'engineer')}:{os.getenv('CAE_API_PASSWORD', 'engineer123')}"
    backend = Api(
        origin(options.backend_url),
        {"Authorization": "Basic " + base64.b64encode(credentials.encode()).decode()},
    )
    services = LocalServices()
    broker = Api(origin(options.broker_url), services.rabbit_auth())
    workers = {
        node: Api(
            origin(url),
            {"X-Service-Token": os.getenv("SERVICE_TOKEN", "demo-service-token")},
        )
        for node, url in (
            entry.split("=", 1) for entry in options.worker_urls.split(",")
        )
    }
    assert set(workers) == {"node-a", "node-b", "node-c"}
    baseline = licenses(backend)
    assert all(reserved == 0 and used == 0 for reserved, used in baseline.values())
    ready_nodes = until(
        lambda: backend.require("GET", "/api/v1/compute-nodes"),
        lambda rows: fresh_nodes(rows, workers),
        "three ONLINE nodes with fresh reachable heartbeat observations",
        timeout=35,
    )
    baseline_nodes = node_ledger(ready_nodes)
    assert all(
        all(value == 0 for value in counters) for counters in baseline_nodes.values()
    )
    catalog = backend.require("GET", "/api/v1/cases")
    assert len(catalog) == 9
    selected = [
        next(row for row in catalog if row["casePath"] == "structure/case-current"),
        next(
            row
            for row in catalog
            if row["analysisType"] == "STRUCTURE" and "MODAL" in row["conditionCode"]
        ),
    ]
    selected.extend(
        next(row for row in catalog if row["analysisType"] == analysis)
        for analysis in ("NVH", "CRASH", "CFD")
    )
    assert selected[1]["conditionCode"] == "BIW_MODAL" and selected[1]["subcase"] == 2
    historic = json.loads(
        (services.repo / "docs/validation/m5-messaging-smoke.json").read_text()
    )["deadLetter"]["logicalEventId"]
    assert queue_message(broker, "cae.job.submit.dlq", historic)
    assert (
        broker.require("GET", "/api/queues/%2F/cae.job.postprocess")["consumers"] == 0
    )
    owned, scenarios = [], []
    try:
        for entry in selected:
            progress(
                f"Running actual {entry['analysisType']} Solver and parser for {entry['conditionCode']}"
            )
            job, raw, raw_artifact = create_case(backend, services, entry, owned)
            execution, identity = execution_identity(backend, workers, job)
            checked = verify_version(
                backend, services, broker, job, "v1", raw, execution
            )
            assert checked["record"]["rawChecksum"] == raw_artifact["checksum"]
            scenarios.append(
                {
                    "jobNo": job["jobNo"],
                    "analysisType": job["analysisType"],
                    "conditionCode": raw["conditionCode"],
                    "subcase": raw["subcase"],
                    "executionId": execution["id"],
                    "processIdentity": identity["processIdentity"],
                    "parserVersion": "v1",
                    "parseRecordId": checked["record"]["id"],
                    "maturity": checked["record"]["maturity"],
                    "metricCount": len(checked["metrics"]),
                    "rawChecksum": raw_artifact["checksum"],
                    "artifactChecksums": {
                        row["artifactType"]: row["checksum"]
                        for row in checked["artifacts"]
                    },
                    "rawValuesMatchAllCanonicalMetrics": True,
                    "postCommandLogicalId": checked["postCommandLogicalId"],
                    "postQueueMessagePreserved": True,
                }
            )
            if not scenarios[:-1]:
                duplicate = duplicate_case(
                    backend,
                    services,
                    broker,
                    workers,
                    job,
                    raw,
                    execution,
                    identity,
                    checked,
                )
                reparse = reparse_case(
                    backend,
                    services,
                    broker,
                    workers,
                    job,
                    raw,
                    execution,
                    identity,
                    checked,
                )
    finally:
        pending_failure = sys.exc_info()[0] is not None
        for job in owned:
            try:
                current = backend.require("GET", f"/api/v1/jobs/{job['jobNo']}")
                if current["status"] not in {
                    "POST_PROCESSING",
                    "POST_PROCESS_FAILED",
                    "RUNTIME_FAILED",
                    "CANCELLED",
                    "SUCCESS",
                }:
                    backend.require("POST", f"/api/v1/jobs/{job['jobNo']}/cancel", {})
            except Exception:
                progress("Owned task cleanup requires inspection: " + job["jobNo"])
                if not pending_failure:
                    raise
    until(
        lambda: licenses(backend),
        lambda value: value == baseline,
        "license baseline restoration",
    )
    until(
        lambda: node_ledger(backend.require("GET", "/api/v1/compute-nodes")),
        lambda value: value == baseline_nodes,
        "CPU and memory reservation/used ledger baseline restoration",
    )
    assert queue_message(broker, "cae.job.submit.dlq", historic)
    m6_relay = []
    m6 = json.loads(
        (services.repo / "docs/validation/m6-reconciliation-smoke.json").read_text()
    )
    for scenario in m6["scenarios"]:
        if scenario["workerState"] != "SUCCESS":
            continue
        job = backend.require("GET", f"/api/v1/jobs/{scenario['jobNo']}")
        record = complete_record(backend, job, "v1")
        assert record["executionId"] == scenario["executionId"]
        rows = until(
            lambda: version_command(services, job, "POST_PROCESS_REQUESTED", "v1"),
            lambda rows: len(rows) == 1 and rows[0]["status"] == "SENT",
            "M6 parse command relay",
        )
        assert queue_message(broker, "cae.job.postprocess", rows[0]["logicalEventId"])
        m6_relay.append(
            {
                "jobNo": job["jobNo"],
                "parseRecordId": record["id"],
                "postCommandLogicalId": rows[0]["logicalEventId"],
                "postQueueMessagePreserved": True,
            }
        )
    progress("M7 smoke passed; post commands remain queued for the real M8 processor")
    print(
        json.dumps(
            {
                "milestone": 7,
                "scenarios": scenarios,
                "duplicateParse": duplicate,
                "versionedReparse": reparse,
                "m6CommandsRelayed": m6_relay,
                "historicalDeadLetterPreserved": historic,
                "caseCatalogCount": len(catalog),
                "threeFreshNodeHeartbeatsVerified": True,
                "nodeLedgerBaseline": baseline_nodes,
                "nodeLedgerReturnedToBaseline": True,
                "licenseLedgerReturnedToBaseline": True,
                "scope": "Actual four-discipline parsing and versioned immutable JSON/CSV; post/report completion awaits M8",
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
