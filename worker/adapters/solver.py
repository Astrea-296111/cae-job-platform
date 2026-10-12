"""Explicit public execution adapters and commercial extension contracts."""

import sys
from pathlib import Path
from typing import Protocol

from app.errors import AgentError


class SolverAdapter(Protocol):
    def command(self, config: Path) -> list[str]: ...


class MockSolverAdapter:
    def command(self, config: Path) -> list[str]:
        return [sys.executable, "-m", "mock_solver", "--config", str(config)]


class MockOptiStructAdapter(MockSolverAdapter):
    """Public structural/modal mock execution."""


class MockNvhAdapter(MockSolverAdapter):
    """Public NVH pilot mock execution."""


class MockCrashAdapter(MockSolverAdapter):
    """Public crash pilot mock execution."""


class MockCfdAdapter(MockSolverAdapter):
    """Public CFD pilot mock execution."""


class OptiStructAdapter(SolverAdapter, Protocol):
    """Commercial SPI; deployment supplies a fixed installation/profile implementation."""


class BatchSchedulerAdapter(Protocol):
    """Enterprise SLURM/PBS SPI; stable request identity remains mandatory."""

    def submit(self, request: dict) -> dict: ...
    def query(self, request_id: str) -> dict: ...
    def cancel(self, external_id: str) -> dict: ...


class HyperViewAdapter(Protocol):
    """Commercial controlled-spec rendering SPI; default rendering remains synthetic."""

    def render(self, specification: dict, workspace: Path) -> list[dict]: ...


_ADAPTERS: dict[str, SolverAdapter] = {
    "STRUCTURE": MockOptiStructAdapter(),
    "NVH": MockNvhAdapter(),
    "CRASH": MockCrashAdapter(),
    "CFD": MockCfdAdapter(),
}


def public_solver(analysis: str) -> SolverAdapter:
    if analysis not in _ADAPTERS:
        raise AgentError("SUBMIT_REJECTED", "Unknown public Solver adapter")
    return _ADAPTERS[analysis]
