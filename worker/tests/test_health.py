from fastapi.testclient import TestClient

from app.main import create_app


def test_health_returns_ok_and_configured_node(monkeypatch, tmp_path):
    monkeypatch.setenv("DATA_ROOT", str(tmp_path))
    monkeypatch.setenv("NODE_ID", "node-c")
    with TestClient(create_app()) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "OK", "nodeId": "node-c"}


def test_health_uses_demo_node_by_default(monkeypatch, tmp_path):
    monkeypatch.setenv("DATA_ROOT", str(tmp_path))
    monkeypatch.delenv("NODE_ID", raising=False)
    with TestClient(create_app()) as client:
        assert client.get("/health").json()["nodeId"] == "node-a"
