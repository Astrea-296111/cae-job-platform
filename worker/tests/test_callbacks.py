import asyncio
import json
import threading
import time
from contextlib import contextmanager
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx
from conftest import prepare_request, wait_terminal
from fastapi.testclient import TestClient

from agents.callbacks import CALLBACK_FIELDS, MAX_CONCURRENCY, PAGE_SIZE, CallbackNotifier
from agents.process_registry import utc_now
from app.main import create_app

HEADERS = {"X-Service-Token": "demo-service-token"}


def eventually(predicate, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError("Callback condition did not become true")


@contextmanager
def recording_backend(settings, reply=None):
    received = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(
                {
                    "path": self.path,
                    "token": self.headers.get("X-Service-Token"),
                    "payload": payload,
                }
            )
            status, body, headers = (200, {"accepted": True}, {})
            if reply and self.path.endswith("/callback"):
                status, body, headers = reply(payload)
            self.send_response(status)
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(json.dumps(body).encode())

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    configured = replace(
        settings,
        backend_url=f"http://127.0.0.1:{server.server_port}",
        heartbeat_interval=0.05,
        callback_interval=0.03,
    )
    try:
        yield configured, received
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def callbacks(received):
    return [event for event in received if event["path"].endswith("/callback")]


def test_actual_process_callbacks_identity_progress_token_and_terminal_ack(settings):
    request = prepare_request(settings.data_root, runtimeSeconds=0.8)
    with recording_backend(settings) as (configured, received):
        with TestClient(create_app(configured)) as client:
            accepted = client.post(
                "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), headers=HEADERS
            ).json()
            external_id = accepted["externalExecutionId"]
            eventually(
                lambda: any(event["payload"]["state"] == "SUCCESS" for event in callbacks(received))
            )
            events = callbacks(received)
            assert any(
                event["payload"]["state"] == "RUNNING" and 0 < event["payload"]["progress"] < 100
                for event in events
            )
            for event in events:
                assert event["path"] == "/internal/v1/agents/node-a/executions/callback"
                assert event["token"] == "demo-service-token"
                assert set(event["payload"]) == set(CALLBACK_FIELDS)
                assert event["payload"]["executionRequestId"] == request.execution_request_id
                assert event["payload"]["externalExecutionId"] == external_id
                assert "workspace" not in event["payload"]
            terminal = events[-1]["payload"]
            assert terminal["progress"] == 100
            assert terminal["pid"] > 0
            assert terminal["processIdentity"].startswith(f"{terminal['pid']}:")
            assert terminal["startedAt"] and terminal["finishedAt"]
            assert terminal["exitCode"] == 0
            count = len(events)
            time.sleep(0.2)
            assert len(callbacks(received)) == count
            heartbeat_task = client.app.state.heartbeat_task
            callback_task = client.app.state.callback_task
        assert heartbeat_task.cancelled()
        assert callback_task.cancelled()


def test_failed_terminal_delivery_retries_without_stopping_heartbeat_or_agent(settings):
    failures = []

    def reply(payload):
        if payload["state"] == "SUCCESS" and len(failures) < 2:
            failures.append(payload)
            return 503, {"accepted": False}, {}
        return 200, {"accepted": True, "applied": False}, {}

    request = prepare_request(settings.data_root)
    with recording_backend(settings, reply) as (configured, received):
        with TestClient(create_app(configured)) as client:
            accepted = client.post(
                "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), headers=HEADERS
            ).json()
            eventually(lambda: len(failures) == 1)
            heartbeat_count = len(received) - len(callbacks(received))
            assert client.get("/health").json()["status"] == "OK"
            eventually(
                lambda: len(
                    [
                        event
                        for event in callbacks(received)
                        if event["payload"]["state"] == "SUCCESS"
                    ]
                )
                >= 3
            )
            assert len(received) - len(callbacks(received)) > heartbeat_count
            terminal = wait_terminal(client.app.state.manager, accepted["externalExecutionId"])
            assert terminal["state"] == "SUCCESS"
            assert len(client.app.state.manager.registry.all()) == 1
            assert (
                Path(terminal["workspace"], "solver.log").read_text().count("Mock Solver RUNNING")
                == 1
            )
            count = len(callbacks(received))
            time.sleep(0.2)
            assert len(callbacks(received)) == count


def test_api_restart_reconciles_same_live_process_and_replays_terminal_once(settings):
    request = prepare_request(settings.data_root, runtimeSeconds=0.7)
    with recording_backend(settings) as (configured, received):
        with TestClient(create_app(configured)) as first:
            accepted = first.post(
                "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), headers=HEADERS
            ).json()
            identity = first.app.state.manager.query(accepted["externalExecutionId"])[
                "processIdentity"
            ]
            eventually(
                lambda: any(event["payload"]["state"] == "RUNNING" for event in callbacks(received))
            )
        before = len(callbacks(received))
        with TestClient(create_app(configured)) as restarted:
            duplicate = restarted.post(
                "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), headers=HEADERS
            ).json()
            assert duplicate["externalExecutionId"] == accepted["externalExecutionId"]
            eventually(
                lambda: any(
                    event["payload"]["state"] == "SUCCESS" for event in callbacks(received)[before:]
                )
            )
            terminal = restarted.app.state.manager.query(accepted["externalExecutionId"])
            assert terminal["processIdentity"] == identity
        before = len(callbacks(received))
        with TestClient(create_app(configured)) as replayed:
            eventually(lambda: len(callbacks(received)) > before)
            assert callbacks(received)[before]["payload"]["state"] == "SUCCESS"
            assert callbacks(received)[before]["payload"]["processIdentity"] == identity
            time.sleep(0.15)
            assert len(callbacks(received)) == before + 1
            assert len(replayed.app.state.manager.registry.all()) == 1
            assert (
                Path(terminal["workspace"], "solver.log").read_text().count("Mock Solver RUNNING")
                == 1
            )


def test_callbacks_continue_when_heartbeat_fault_is_disabled(settings):
    request = prepare_request(settings.data_root, runtimeSeconds=0.35)
    with recording_backend(settings) as (configured, received):
        with TestClient(create_app(configured)) as client:
            client.post("/worker/v1/faults/heartbeat", json={"enabled": False}, headers=HEADERS)
            time.sleep(0.1)
            heartbeat_count = len(received) - len(callbacks(received))
            client.post(
                "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), headers=HEADERS
            )
            eventually(
                lambda: any(event["payload"]["state"] == "SUCCESS" for event in callbacks(received))
            )
            assert len(received) - len(callbacks(received)) == heartbeat_count


def test_redirect_is_not_followed_and_terminal_pending_survives_restart(settings):
    allow = {"accepted": False}

    def reply(_payload):
        if not allow["accepted"]:
            return 302, {}, {"Location": "/unsafe-redirect"}
        return 200, {"accepted": True}, {}

    request = prepare_request(settings.data_root)
    with recording_backend(settings, reply) as (configured, received):
        with TestClient(create_app(configured)) as first:
            accepted = first.post(
                "/worker/v1/jobs/submit", json=request.model_dump(by_alias=True), headers=HEADERS
            ).json()
            eventually(
                lambda: any(event["payload"]["state"] == "SUCCESS" for event in callbacks(received))
            )
            terminal = wait_terminal(first.app.state.manager, accepted["externalExecutionId"])
        assert not any(event["path"] == "/unsafe-redirect" for event in received)
        allow["accepted"] = True
        before = len(callbacks(received))
        with TestClient(create_app(configured)) as restarted:
            eventually(lambda: len(callbacks(received)) > before)
            assert callbacks(received)[before]["payload"]["state"] == "SUCCESS"
            assert (
                restarted.app.state.manager.query(accepted["externalExecutionId"])["pid"]
                == terminal["pid"]
            )


def test_bounded_pages_eventually_deliver_all_terminal_records(manager, settings):
    active_request = prepare_request(settings.data_root, runtimeSeconds=3)
    active, _ = manager.submit(active_request)
    count = PAGE_SIZE * 2 + 1
    for index in range(count):
        request = {"executionRequestId": f"old-{index:03d}", "resource": {"cpu": 1, "memoryGb": 1}}
        row, _ = manager.registry.claim(
            request, f"fingerprint-{index}", f"node-a-old-{index}", settings.data_root, 16, 32
        )
        payload = json.loads(row["payload_json"])
        payload.update(state="SUCCESS", progress=100, exitCode=0, finishedAt=utc_now())
        manager.registry.update_payload(request["executionRequestId"], payload)

    outstanding = {"current": 0, "maximum": 0}
    lock = threading.Lock()

    def delayed_reply(_payload):
        with lock:
            outstanding["current"] += 1
            outstanding["maximum"] = max(outstanding["maximum"], outstanding["current"])
        try:
            time.sleep(0.03)
            return 200, {"accepted": True}, {}
        finally:
            with lock:
                outstanding["current"] -= 1

    with recording_backend(settings, delayed_reply) as (configured, received):
        manager.settings = configured

        async def send_pages():
            notifier = CallbackNotifier(manager)
            async with httpx.AsyncClient(trust_env=False) as client:
                for _ in range(4):
                    await notifier.scan(client)
                await notifier.scan(client)

        asyncio.run(send_pages())
    assert callbacks(received)[0]["payload"]["externalExecutionId"] == active["externalExecutionId"]
    terminal_events = [
        event
        for event in callbacks(received)
        if event["payload"]["executionRequestId"].startswith("old-")
    ]
    assert len(terminal_events) == count
    assert len({event["payload"]["executionRequestId"] for event in terminal_events}) == count
    assert 1 < outstanding["maximum"] <= MAX_CONCURRENCY
