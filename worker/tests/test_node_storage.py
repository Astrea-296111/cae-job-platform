import os
from dataclasses import replace

from agents.execution_manager import ExecutionManager


def test_workspace_reachable_before_first_job_and_when_jobs_directory_exists(manager, settings):
    assert not (settings.data_root / "jobs").exists()
    assert manager.node_status()["workspaceReachable"] is True
    (settings.data_root / "jobs").mkdir()
    assert manager.node_status()["workspaceReachable"] is True


def test_workspace_unreachable_when_data_root_does_not_exist(settings, tmp_path):
    configured = replace(
        settings,
        data_root=tmp_path / "missing-shared-filesystem",
        registry_path=tmp_path / "separate-local-agent" / "registry.sqlite3",
    )
    manager = ExecutionManager(configured)
    assert not configured.data_root.exists()
    assert manager.node_status()["workspaceReachable"] is False


def test_workspace_unreachable_when_data_root_lacks_required_access(manager, monkeypatch):
    checks = []

    def inaccessible(path, mode):
        checks.append((path, mode))
        return False

    monkeypatch.setattr(os, "access", inaccessible)
    assert manager.node_status()["workspaceReachable"] is False
    assert checks == [(manager.settings.data_root, os.R_OK | os.W_OK | os.X_OK)]


def test_workspace_unreachable_when_jobs_path_is_a_file(manager, settings):
    (settings.data_root / "jobs").write_text("Not a directory")
    assert manager.node_status()["workspaceReachable"] is False
