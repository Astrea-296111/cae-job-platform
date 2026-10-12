"""Bind immutable snapshots and selected parser exports before drawing any output."""

import hashlib
import json
import os
from decimal import Decimal
from pathlib import Path

from agents.files import ensure_contained, verify_snapshot
from app.contracts import SubmitRequest
from app.errors import AgentError


def fail(message):
    raise AgentError("RENDER_SOURCE_INVALID", message, 409)


def read_bytes(root: Path, path: Path, limit=32 * 1024 * 1024):
    ensure_contained(root, path)
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, "rb") as stream:
            if not path.is_file() or os.fstat(stream.fileno()).st_size > limit:
                fail("Source size/type is invalid")
            data = stream.read(limit + 1)
        if len(data) > limit:
            fail("Source exceeds size limit")
        ensure_contained(root, path)
        return data
    except OSError as error:
        raise AgentError("RENDER_SOURCE_INVALID", "Source file unavailable", 409) from error


def load_json(root, path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail("Duplicate JSON key")
            result[key] = value
        return result

    encoded = read_bytes(root, path, 2 * 1024 * 1024)
    try:
        value = json.loads(
            encoded,
            parse_float=Decimal,
            parse_constant=lambda _: fail("Nonfinite JSON"),
            object_pairs_hook=unique,
        )
    except (ValueError, TypeError) as error:
        raise AgentError("RENDER_SOURCE_INVALID", "Invalid source JSON", 409) from error
    if not isinstance(value, dict):
        fail("JSON source must be an object")
    return value, hashlib.sha256(encoded).hexdigest()


def verify_source(settings, request):
    root = settings.data_root
    job = root / "jobs" / request.job_no
    workspace = job / "executions" / request.execution_request_id
    config, _ = load_json(root, workspace / "execution-config.json")
    state, _ = load_json(root, workspace / "state.json")
    for key, expected in {
        "jobNo": request.job_no,
        "executionRequestId": request.execution_request_id,
        "externalExecutionId": request.external_execution_id,
        "nodeId": settings.node_id,
        "workspace": str(workspace),
        "inputDirectory": str(job / "input"),
        "analysisType": request.analysis_type,
        "conditionCode": request.condition_code,
        "snapshotChecksum": request.snapshot_checksum,
    }.items():
        if config.get(key) != expected:
            fail("Execution configuration identity mismatch: " + key)
    if request.node_id != settings.node_id:
        fail("Wrong rendering node")
    for key, expected in {
        "state": "SUCCESS",
        "exitCode": 0,
        "executionRequestId": request.execution_request_id,
        "externalExecutionId": request.external_execution_id,
        "workspace": str(workspace),
        "pid": request.pid,
        "processIdentity": request.process_identity,
    }.items():
        if state.get(key) != expected:
            fail("Successful execution identity mismatch: " + key)
    submit_data = {
        (field.alias or name): config[field.alias or name]
        for name, field in SubmitRequest.model_fields.items()
    }
    submit_data["workspace"] = str(job)
    submit = SubmitRequest.model_validate(submit_data)
    verify_snapshot(submit, root)
    manifest, _ = load_json(root, job / "input" / "manifest.json")
    parameters = manifest.get("parameters", {})
    raw, raw_sha = load_json(root, workspace / "raw_result.json")
    result, result_sha = load_json(root, job / "derived" / request.parser_version / "result.json")
    if raw_sha != request.raw_checksum or result_sha != request.result_json_checksum:
        fail("Selected result checksum mismatch")
    if (
        parameters != config.get("parameters")
        or parameters != raw.get("parameters")
        or raw.get("seed")
        != (
            config["mock"].get("seed")
            if config["mock"].get("seed") is not None
            else parameters.get("seed", 42)
        )
    ):
        fail("Synthetic parameter identity mismatch")
    for document in (raw, result):
        if (
            document.get("analysisType"),
            document.get("conditionCode"),
            document.get("subcase"),
        ) != (request.analysis_type, request.condition_code, request.subcase):
            fail("Source condition/subcase mismatch")
    for key, expected in {
        "jobNo": request.job_no,
        "executionId": request.execution_id,
        "executionRequestId": request.execution_request_id,
        "parserVersion": request.parser_version,
        "rawChecksum": request.raw_checksum,
        "maturity": request.context.maturity,
    }.items():
        if result.get(key) != expected:
            fail("Selected parser identity mismatch: " + key)
    if parameters.get("subcase", 1) != request.subcase:
        fail("Snapshot subcase mismatch")
    exported = {row["metricCode"]: row for row in result.get("metrics", [])}
    if len(exported) != len(request.metrics):
        fail("Incomplete canonical metric set")
    for metric in request.metrics:
        row = exported.get(metric.metric_code, {})
        expected = metric.model_dump(by_alias=True)
        for key, value in expected.items():
            actual = row.get(key)
            if key == "valueNum" and value is not None:
                if actual is None or Decimal(str(actual)) != Decimal(value):
                    fail("Canonical numeric value mismatch")
            elif actual != value:
                fail("Canonical metric metadata mismatch: " + key)
    if (
        request.parser_version == "v2"
        and result.get("provenance", {}).get("snapshotChecksum") != request.snapshot_checksum
    ):
        fail("Parser provenance mismatch")
    return raw
