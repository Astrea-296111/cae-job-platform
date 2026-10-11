"""Whitelisted subprocess entry point; never executes uploaded scripts."""

import argparse
import json
import os
import random
import signal
import time
from pathlib import Path

import psutil

from agents.files import atomic_json
from agents.process_registry import utc_now


def synthetic_result(config: dict, manifest: dict) -> dict:
    parameters = manifest.get("parameters", {})
    seed = config["mock"].get("seed")
    if seed is None:
        seed = parameters.get("seed", 42)
    generator = random.Random(seed)
    scale = float(parameters.get("thicknessScale", 1))
    analysis = config["analysisType"]
    result = {
        "schemaVersion": 1,
        "analysisType": analysis,
        "conditionCode": config["conditionCode"],
        "subcase": parameters.get("subcase", 1),
        "solverProfile": config["solverProfile"],
        "seed": seed,
        "parameters": parameters,
    }
    if analysis == "STRUCTURE":
        result.update(
            mass_kg=round((310 + generator.uniform(-8, 8)) * scale, 3),
            torsional_stiffness_nm_per_deg=round(
                (21000 + generator.uniform(-900, 900)) * scale**1.4, 3
            ),
            bending_stiffness_n_per_mm=round((18500 + generator.uniform(-700, 700)) * scale, 3),
            modes_hz=[
                round((frequency + generator.uniform(-0.8, 0.8)) * scale**0.5, 3)
                for frequency in (28.6, 31.9, 42.3, 49.8)
            ],
        )
    elif analysis == "NVH":
        result.update(
            peak_frequency_hz=round(85 + generator.uniform(-10, 10), 3),
            peak_response_db=round(62 + generator.uniform(-5, 5), 3),
        )
    elif analysis == "CRASH":
        result.update(
            max_intrusion_mm=round(140 + generator.uniform(-12, 12), 3),
            peak_acceleration_g=round(35 + generator.uniform(-5, 5), 3),
            energy_balance_ratio=round(0.98 + generator.uniform(-0.01, 0.01), 4),
        )
    elif analysis == "CFD":
        result.update(
            cd=round(0.278 + generator.uniform(-0.015, 0.015), 5),
            cl=round(0.045 + generator.uniform(-0.01, 0.01), 5),
            residual_final=1e-6,
            converged=True,
        )
    return result


def run(config: dict) -> int:
    output = Path(config["workspace"])
    manifest = json.loads(Path(config["inputDirectory"], "manifest.json").read_text())
    state_path = output / "state.json"
    cancelled = False

    def cancel_signal(_number, _frame):
        nonlocal cancelled
        cancelled = True

    signal.signal(signal.SIGTERM, cancel_signal)
    signal.signal(signal.SIGINT, cancel_signal)
    identity = psutil.Process().create_time()
    state = {
        "externalExecutionId": config["externalExecutionId"],
        "executionRequestId": config["executionRequestId"],
        "state": "RUNNING",
        "workspace": str(output),
        "pid": os.getpid(),
        "createTime": identity,
        "processIdentity": f"{os.getpid()}:{identity:.6f}",
        "exitCode": None,
        "rawSolverCode": None,
        "rawMessage": None,
        "progress": 0,
        "startedAt": utc_now(),
        "finishedAt": None,
    }
    started = time.monotonic()
    runtime = config["mock"]["runtimeSeconds"]
    interval = config["mock"]["progressInterval"]
    deadline = config["resource"]["walltimeMin"] * 60
    with (output / "solver.log").open("a", encoding="utf-8", buffering=1) as log:
        log.write(
            f"{utc_now()} jobNo={config['jobNo']} "
            f"externalExecutionId={config['externalExecutionId']} "
            f"nodeId={config['nodeId']} pid/processIdentity={state['processIdentity']} "
            "Mock Solver RUNNING\n"
        )
        with (output / "progress.jsonl").open("a", encoding="utf-8", buffering=1) as progress:
            while True:
                elapsed = time.monotonic() - started
                if cancelled or (output / "cancel.request").exists():
                    state.update(
                        state="CANCELLED", exitCode=143, rawMessage="Cancellation confirmed"
                    )
                    break
                if elapsed >= deadline:
                    state.update(
                        state="TIMEOUT",
                        exitCode=124,
                        rawSolverCode="MOCK_WALLTIME_EXCEEDED",
                        rawMessage="Configured walltime was exceeded",
                    )
                    break
                if elapsed >= runtime:
                    mode = config["mock"]["failureMode"]
                    seed = config["mock"].get("seed")
                    if seed is None:
                        seed = manifest.get("parameters", {}).get("seed", 42)
                    if mode == "TIMEOUT":
                        state.update(
                            state="TIMEOUT",
                            exitCode=124,
                            rawSolverCode="MOCK_TIMEOUT",
                            rawMessage="Injected synthetic solver timeout",
                        )
                    elif (
                        mode == "RUNTIME_FAILED"
                        or random.Random(seed).random() > config["mock"]["successProbability"]
                    ):
                        state.update(
                            state="RUNTIME_FAILED",
                            exitCode=2,
                            rawSolverCode="MOCK_NUMERICAL_FAILURE",
                            rawMessage="Injected synthetic solver runtime failure",
                        )
                    else:
                        atomic_json(output / "raw_result.json", synthetic_result(config, manifest))
                        state.update(state="SUCCESS", exitCode=0, progress=100)
                    break
                state["progress"] = min(99, round(elapsed / runtime * 100, 2))
                atomic_json(state_path, state)
                progress.write(
                    json.dumps({"occurredAt": utc_now(), "progress": state["progress"]}) + "\n"
                )
                time.sleep(min(interval, max(0.02, runtime - elapsed)))
        state["finishedAt"] = utc_now()
        log.write(
            f"{utc_now()} {state['state']} exitCode={state['exitCode']} "
            f"{state['rawMessage'] or ''}\n"
        )
        log.flush()
        os.fsync(log.fileno())
    # A terminal observation is also the publication point for immutable output artifacts.
    # Publish after closing the log so a parser cannot register a still-changing checksum.
    atomic_json(state_path, state)
    return state["exitCode"]


def main() -> int:
    parser = argparse.ArgumentParser(description="Synthetic CAE Solver")
    parser.add_argument("--config", type=Path, required=True)
    options = parser.parse_args()
    return run(json.loads(options.config.read_text(encoding="utf-8")))
