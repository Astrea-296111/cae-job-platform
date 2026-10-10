#!/usr/bin/env python3
"""Milestone 3 live Agent checks; backend jobs remain READY, not pipeline-complete."""

import argparse
import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Api:
    def __init__(self, origin, headers):
        self.origin = origin.rstrip("/")
        self.headers = headers
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, payload=None, timeout=5, authenticated=True):
        headers = {"Accept": "application/json"}
        if authenticated:
            headers.update(self.headers)
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            self.origin + path,
            data=None if payload is None else json.dumps(payload).encode(),
            headers=headers,
            method=method,
        )
        try:
            with self.opener.open(request, timeout=timeout) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def require(self, method, path, payload=None, timeout=5):
        status, body = self.call(method, path, payload, timeout)
        if not 200 <= status < 300:
            raise AssertionError(f"{method} {path}: HTTP {status}, code={body.get('code')}")
        return body


def wait_for(probe, predicate, description, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = probe()
        if predicate(result):
            return result
        time.sleep(0.1)
    raise AssertionError(f"Timed out waiting for {description}")


def assert_identity(expected, actual):
    for field in ("externalExecutionId", "executionRequestId", "pid", "processIdentity"):
        assert expected.get(field) == actual.get(field), f"Execution identity changed: {field}"
    assert isinstance(actual.get("pid"), int) and actual["pid"] > 0, "No real solver PID"
    assert isinstance(actual.get("processIdentity"), str), "No process start identity"


def new_execution(backend, runtime, response_delay=0):
    created = backend.require(
        "POST",
        "/api/v1/jobs",
        {
            "projectId": 1,
            "modelRevisionId": 2,
            "analysisType": "STRUCTURE",
            "conditionCode": "BIW_TORSION",
            "casePath": "structure/case-good",
            "resourceProfile": "SMALL",
        },
    )
    job_no = created["jobNo"]
    job = backend.require("POST", f"/api/v1/jobs/{job_no}/validate")
    assert job.get("status") == "READY", "Case must validate before Agent submission"
    return job, {
        "executionRequestId": "m3-" + uuid.uuid4().hex,
        "jobNo": job_no,
        "workspace": job["workspace"],
        "solverProfile": job["solverProfile"],
        "analysisType": job["analysisType"],
        "conditionCode": job["conditionCode"],
        "snapshotChecksum": job["inputChecksum"],
        "resource": job["resource"],
        "mock": {
            "runtimeSeconds": runtime,
            "progressInterval": 0.05,
            "responseDelayOnceSeconds": response_delay,
            "seed": 37,
        },
    }


def terminal(agent, external_id):
    state = wait_for(
        lambda: agent.require("GET", f"/worker/v1/jobs/{external_id}"),
        lambda body: body.get("state") in {"SUCCESS", "RUNTIME_FAILED", "TIMEOUT", "CANCELLED"},
        "terminal solver state",
    )
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node-url", default="http://127.0.0.1:8001")
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    options = parser.parse_args()
    for origin in (options.node_url, options.backend_url):
        parsed = urllib.parse.urlsplit(origin)
        assert parsed.scheme in {"http", "https"} and parsed.hostname, (
            "Use an HTTP(S) service origin"
        )
        assert not parsed.username and not parsed.password, (
            "Credentials belong in environment variables"
        )

    user = os.getenv("CAE_API_USER", "engineer")
    password = os.getenv("CAE_API_PASSWORD", "engineer123")
    encoded = base64.b64encode(f"{user}:{password}".encode()).decode()
    backend = Api(options.backend_url, {"Authorization": "Basic " + encoded})
    agent = Api(
        options.node_url, {"X-Service-Token": os.getenv("SERVICE_TOKEN", "demo-service-token")}
    )

    status, unauthorized = agent.call("GET", "/worker/v1/node/status", authenticated=False)
    assert status == 401 and unauthorized.get("code") == "UNAUTHENTICATED", (
        "Agent token is not enforced"
    )
    assert agent.require("GET", "/health").get("status") == "OK", "Agent health failed"
    node = agent.require("GET", "/worker/v1/node/status")
    assert node.get("nodeId") == "node-a", "This smoke uses node-a's STRUCTURE profile"

    summaries = []
    normal_job, normal_request = new_execution(backend, runtime=1)
    accepted = agent.require("POST", "/worker/v1/jobs/submit", normal_request)
    assert accepted.get("accepted") is True, "Agent did not accept valid execution"
    external_id = accepted["externalExecutionId"]
    identity = agent.require("GET", f"/worker/v1/jobs/{external_id}")
    for _ in range(3):
        duplicate = agent.require("POST", "/worker/v1/jobs/submit", normal_request)
        assert duplicate["externalExecutionId"] == external_id, (
            "Duplicate submit started another execution"
        )
        current = agent.require(
            "GET", "/worker/v1/jobs/by-request/" + normal_request["executionRequestId"]
        )
        assert_identity(identity, current)
    completed = terminal(agent, external_id)
    assert completed["state"] == "SUCCESS", "Normal synthetic solver failed"
    assert_identity(identity, completed)
    summaries.append(
        {
            "scenario": "duplicate-submit",
            "jobNo": normal_job["jobNo"],
            "externalExecutionId": external_id,
            "processIdentity": completed["processIdentity"],
            "workerState": completed["state"],
        }
    )

    timeout_job, timeout_request = new_execution(backend, runtime=2.5, response_delay=2)
    try:
        agent.call("POST", "/worker/v1/jobs/submit", timeout_request, timeout=0.2)
        raise AssertionError("Injected accepted response did not time out")
    except TimeoutError:
        pass
    except urllib.error.URLError as error:
        if not isinstance(error.reason, TimeoutError):
            raise

    request_path = "/worker/v1/jobs/by-request/" + timeout_request["executionRequestId"]
    recovered = wait_for(
        lambda: agent.call("GET", request_path),
        lambda response: response[0] == 200,
        "accepted execution after HTTP timeout",
    )[1]
    assert isinstance(recovered.get("pid"), int), "Timeout happened before process acceptance"
    retried = agent.require("POST", "/worker/v1/jobs/submit", timeout_request, timeout=1)
    assert retried["externalExecutionId"] == recovered["externalExecutionId"], (
        "Response timeout retry started another execution"
    )
    assert_identity(recovered, agent.require("GET", request_path))
    completed = terminal(agent, recovered["externalExecutionId"])
    assert completed["state"] == "SUCCESS", "Timed-out HTTP response must not stop the solver"
    assert_identity(recovered, completed)
    summaries.append(
        {
            "scenario": "accepted-response-timeout",
            "jobNo": timeout_job["jobNo"],
            "externalExecutionId": completed["externalExecutionId"],
            "processIdentity": completed["processIdentity"],
            "workerState": completed["state"],
        }
    )

    cancel_job, cancel_request = new_execution(backend, runtime=5)
    accepted = agent.require("POST", "/worker/v1/jobs/submit", cancel_request)
    external_id = accepted["externalExecutionId"]
    running = wait_for(
        lambda: agent.require("GET", f"/worker/v1/jobs/{external_id}"),
        lambda body: body.get("state") == "RUNNING",
        "running execution before cancellation",
    )
    agent.require("POST", f"/worker/v1/jobs/{external_id}/cancel", {})
    cancelled = terminal(agent, external_id)
    assert cancelled["state"] == "CANCELLED", "Remote cancellation was not confirmed"
    assert_identity(running, cancelled)
    repeated_cancel = agent.require("POST", f"/worker/v1/jobs/{external_id}/cancel", {})
    assert repeated_cancel["state"] == "CANCELLED", "Repeated cancel changed terminal state"
    summaries.append(
        {
            "scenario": "running-cancel",
            "jobNo": cancel_job["jobNo"],
            "externalExecutionId": external_id,
            "processIdentity": cancelled["processIdentity"],
            "workerState": cancelled["state"],
        }
    )

    for summary in summaries:
        job = backend.require("GET", "/api/v1/jobs/" + summary["jobNo"])
        assert job["status"] == "READY", (
            "This M3 smoke must not claim a backend pipeline completion"
        )
        summary["backendState"] = job["status"]
    print(
        json.dumps(
            {
                "milestone": 3,
                "serviceAuthentication": "passed",
                "scenarios": summaries,
                "scope": "Agent subprocess protocol; backend jobs remain READY",
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
