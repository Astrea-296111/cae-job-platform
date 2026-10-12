#!/usr/bin/env python3
"""Measured local HTTP workload plus isolated Java ledger/publisher samples."""

import concurrent.futures
import datetime
import json
import math
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
from agent_smoke import Api
from messaging_smoke import LocalServices
from report_smoke import user
from reconciliation_smoke import until

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / ".local/benchmark"
OUTPUT.mkdir(parents=True, exist_ok=True)


def progress(message):
    print("[LOCAL-BENCH] " + message, file=sys.stderr, flush=True)


def summarize(samples):
    times = sorted(float(row["ms"]) for row in samples)
    failures = sum(row.get("outcome") == "error" for row in samples)

    def percentile(p):
        return times[max(0, math.ceil(len(times) * p) - 1)] if times else None

    return {
        "samples": len(times),
        "p50Ms": percentile(0.50),
        "p95Ms": percentile(0.95),
        "p99Ms": percentile(0.99),
        "errors": failures,
        "errorRatePercent": 100 * failures / len(times) if times else None,
    }


def measured(function):
    at = time.perf_counter()
    try:
        value = function()
        return {
            "ms": (time.perf_counter() - at) * 1000,
            "outcome": "ok",
            "value": value,
        }
    except Exception as failure:
        return {
            "ms": (time.perf_counter() - at) * 1000,
            "outcome": "error",
            "error": type(failure).__name__,
        }


def parallel(count, workers, function):
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        return list(
            executor.map(lambda index: measured(lambda: function(index)), range(count))
        )


def main():
    api = user("admin", "admin123")
    services = LocalServices()
    observations = []
    stopped = threading.Event()
    container = subprocess.run(
        [*services.compose, "ps", "-q", "mysql"],
        cwd=ROOT,
        env=services.environment,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()

    def observe():
        while not stopped.is_set():
            try:
                stats = subprocess.run(
                    [
                        "docker",
                        "stats",
                        "--no-stream",
                        "--format",
                        "{{json .}}",
                        container,
                    ],
                    env=services.environment,
                    capture_output=True,
                    text=True,
                    check=True,
                    timeout=10,
                )
                db = services.container(
                    "mysql",
                    "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql --user=root --batch --raw --skip-column-names --execute=\"SELECT JSON_OBJECT('threadsConnected',MAX(IF(VARIABLE_NAME='Threads_connected',VARIABLE_VALUE,NULL)),'threadsRunning',MAX(IF(VARIABLE_NAME='Threads_running',VARIABLE_VALUE,NULL)),'maxUsedConnections',MAX(IF(VARIABLE_NAME='Max_used_connections',VARIABLE_VALUE,NULL))) FROM performance_schema.global_status;\"",
                )
                observations.append(
                    {
                        "atUtc": datetime.datetime.now(
                            datetime.timezone.utc
                        ).isoformat(),
                        "container": json.loads(stats.stdout),
                        "mysql": json.loads(db),
                    }
                )
            except Exception as failure:
                observations.append({"error": type(failure).__name__})
            stopped.wait(3)

    observer = threading.Thread(target=observe, daemon=True)
    observer.start()
    try:
        job = api.require("GET", "/api/v1/jobs?status=SUCCESS&size=1")["items"][0]
        progress("1000 actual Job queries, concurrency 20")
        queries = parallel(
            1000,
            20,
            lambda _: api.require("GET", "/api/v1/jobs/" + job["jobNo"], timeout=30),
        )
        payload = {
            "projectId": 1,
            "modelRevisionId": 2,
            "analysisType": "STRUCTURE",
            "conditionCode": "BIW_TORSION",
            "casePath": "structure/case-current",
            "resourceProfile": "SMALL",
        }
        keys = ["local-bench-" + uuid.uuid4().hex for _ in range(100)]

        def create(index):
            caller = Api(api.origin, {**api.headers, "Idempotency-Key": keys[index]})
            return caller.require("POST", "/api/v1/jobs", payload, timeout=60)

        progress("100 concurrent real Job creations")
        creation = parallel(100, 100, create)
        successful = [
            row["value"]["jobNo"] for row in creation if row["outcome"] == "ok"
        ]
        assert len(successful) == 100 and len(set(successful)) == 100
        assert create(0)["jobNo"] == creation[0]["value"]["jobNo"]
        progress("50 authenticated SSE connections, retaining each real stream")
        responses = []
        stream_job = api.require("GET", "/api/v1/jobs/" + successful[0])

        def sse_count():
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(
                api.origin + "/actuator/prometheus", timeout=10
            ) as response:
                lines = response.read().decode().splitlines()
            return next(
                float(line.split()[1])
                for line in lines
                if line.startswith("cae_sse_connections ")
            )

        before_streams = sse_count()

        def connect(_):
            request = urllib.request.Request(
                api.origin + "/api/v1/events/stream?jobNo=" + stream_job["jobNo"],
                headers={**api.headers, "Accept": "text/event-stream"},
            )
            response = urllib.request.build_opener(
                urllib.request.ProxyHandler({})
            ).open(request, timeout=30)
            assert (
                response.status == 200
                and "text/event-stream" in response.headers["Content-Type"]
            )
            while True:
                line = response.readline()
                if line.startswith(b"data:"):
                    break
                assert line
            responses.append(response)
            return {"ready": True}

        streaming = parallel(50, 50, connect)
        assert len(responses) == 50
        assert sse_count() == before_streams + 50
        for response in responses:
            # An unbounded HTTP body retains a buffered socket reference. Close
            # our actual transport explicitly before releasing the response.
            response.fp.raw._sock.shutdown(socket.SHUT_RDWR)
            response.close()
        cleanup_counts = []

        def disconnected_count():
            count = sse_count()
            cleanup_counts.append(count)
            if count > before_streams:
                api.require(
                    "PATCH",
                    "/api/v1/jobs/" + stream_job["jobNo"] + "/priority",
                    {"priority": stream_job["priority"]},
                )
            return count

        until(
            disconnected_count,
            lambda count: count == before_streams,
            "SSE disconnect cleanup",
            90,
        )
        progress(
            "100 real child Jobs in a bounded batch; measuring admission and completion"
        )
        children = [
            {
                **payload,
                "mock": {
                    "runtimeSeconds": 0.2,
                    "progressInterval": 0.05,
                    "successProbability": 1,
                },
            }
            for _ in range(100)
        ]
        started = time.perf_counter()
        submission = measured(
            lambda: api.require(
                "POST",
                "/api/v1/batches",
                {
                    "name": "[LOCAL-BENCH] 100 Jobs",
                    "projectId": 1,
                    "idempotencyKey": "local-bench-" + uuid.uuid4().hex,
                    "jobs": children,
                },
                timeout=30,
            )
        )
        assert submission["outcome"] == "ok"
        batch = submission["value"]
        number = batch["batchNo"]
        last_note = time.monotonic()

        def get_batch():
            nonlocal last_note
            row = api.require("GET", "/api/v1/batches/" + number, timeout=30)
            if time.monotonic() - last_note >= 10:
                progress("batch " + json.dumps(row["counts"]))
                last_note = time.monotonic()
            return row

        batch = until(
            get_batch, lambda row: row["progress"] == 100, "100 Job batch complete", 600
        )
        completion = time.perf_counter() - started
        assert batch["counts"]["success"] == 100 and batch["counts"]["failed"] == 0, (
            batch["counts"]
        )
        admissions = services.query(
            "SELECT JSON_OBJECT('ms',TIMESTAMPDIFF(MICROSECOND,b.created_at,j.created_at)/1000,'outcome','ok','jobNo',j.job_no) FROM cae_job j JOIN cae_batch b ON b.id=j.batch_id WHERE b.id="
            + str(int(batch["id"]))
            + " ORDER BY j.id;"
        )
        assert len(admissions) == 100
        java = json.loads((OUTPUT / "java.json").read_text())
        resource = java["resourceClaims"]
        publishing = java["outboxPublish"]
        result = {
            "label": "[LOCAL-BENCH]",
            "atUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "environment": {
                "scope": "one local public Compose deployment; shared MySQL CPU samples; Java ledger/publisher in owned schema/vhost",
                "queryConcurrency": 20,
                "createConcurrency": 100,
                "resourceConcurrency": 50,
                "sseConcurrency": 50,
            },
            "jobQuery": summarize(queries),
            "jobCreate": {
                **summarize(creation),
                "uniqueJobs": len(set(successful)),
                "sameKeyReplayedSameJob": True,
            },
            "resourceClaims": {
                **summarize(resource["samples"]),
                "admitted": resource["admitted"],
                "denied": resource["denied"],
                "peakNode": resource["peakNode"],
                "peakLicense": resource["peakLicense"],
            },
            "batchSubmit": {
                "acceptance": summarize([submission]),
                "childAdmission": summarize(admissions),
                "totalJobs": 100,
                "success": 100,
                "completionSeconds": completion,
                "batchNo": number,
            },
            "outboxPublish": {
                **summarize(publishing["samples"]),
                "persistentConfirmed": publishing["confirmed"],
            },
            "sseConnect": {
                **summarize(streaming),
                "actualReadyStreams": len(responses),
                "liveGaugeIncrease": 50,
                "connectionsAfterClose": sse_count(),
                "disconnectGaugeSamples": cleanup_counts,
            },
            "databaseObservations": observations,
            "rawSamples": {
                "jobQuery": [
                    {k: v for k, v in row.items() if k != "value"} for row in queries
                ],
                "jobCreate": [
                    {k: v for k, v in row.items() if k != "value"} for row in creation
                ],
                "sseConnect": [
                    {k: v for k, v in row.items() if k != "value"} for row in streaming
                ],
                "batchAdmission": admissions,
            },
        }
        (OUTPUT / "results.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2)
        )
        print(
            json.dumps(
                {
                    key: value
                    for key, value in result.items()
                    if key not in {"rawSamples", "databaseObservations"}
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    finally:
        stopped.set()
        observer.join(timeout=15)


if __name__ == "__main__":
    main()
