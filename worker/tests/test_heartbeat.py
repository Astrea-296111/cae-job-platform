import json
import threading
import time
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from fastapi.testclient import TestClient

from app.main import create_app


def test_real_heartbeat_payload_token_and_offline_fault_does_not_stop_agent(settings):
    received = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers["Content-Length"])
            received.append(
                (
                    self.path,
                    self.headers.get("X-Service-Token"),
                    json.loads(self.rfile.read(length)),
                )
            )
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"{}")

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    configured = replace(
        settings,
        backend_url=f"http://127.0.0.1:{server.server_port}",
        heartbeat_interval=0.1,
    )
    headers = {"X-Service-Token": "demo-service-token"}
    try:
        with TestClient(create_app(configured)) as client:
            started = time.monotonic()
            while not received:
                assert time.monotonic() - started < 5
                time.sleep(0.02)
            path, token, payload = received[0]
            assert path == "/internal/v1/agents/node-a/heartbeat"
            assert token == "demo-service-token"
            assert payload["solverTags"] == ["STRUCTURE", "NVH", "CRASH", "CFD"]
            assert payload["runningJobs"] == []
            assert payload["cpuTotal"] == 16
            assert payload["memoryTotalGb"] == 32
            client.post("/worker/v1/faults/heartbeat", json={"enabled": False}, headers=headers)
            time.sleep(0.15)  # Let a heartbeat already in flight finish.
            count = len(received)
            time.sleep(0.25)
            assert len(received) == count
            assert client.get("/health").json()["status"] == "OK"
            client.post("/worker/v1/faults/heartbeat", json={"enabled": True}, headers=headers)
            started = time.monotonic()
            while len(received) == count:
                assert time.monotonic() - started < 5
                time.sleep(0.02)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
