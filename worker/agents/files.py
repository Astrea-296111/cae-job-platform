"""Atomic worker output and strict shared filesystem containment."""

import hashlib
import json
import math
import os
import uuid
from pathlib import Path

from app.contracts import SubmitRequest
from app.errors import AgentError


def atomic_json(path: Path, value: dict) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("x", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def ensure_contained(root: Path, path: Path) -> None:
    if not path.is_relative_to(root):
        raise AgentError("INPUT_INVALID", "Workspace is outside the configured data root")
    current = root
    if current.is_symlink():
        raise AgentError("INPUT_INVALID", "Symbolic links are not allowed in workspace paths")
    for part in path.relative_to(root).parts:
        if part in (".", ".."):
            raise AgentError("INPUT_INVALID", "Workspace traversal is not allowed")
        current /= part
        if current.is_symlink():
            raise AgentError("INPUT_INVALID", "Symbolic links are not allowed in workspace paths")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8192), b""):
            digest.update(chunk)
    return digest.hexdigest()


def aggregate_checksum(files: dict[str, str]) -> str:
    encoded = "".join(f"{name}:{files[name]}\n" for name in sorted(files)).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def verify_snapshot(request: SubmitRequest, data_root: Path) -> tuple[Path, dict]:
    workspace = Path(request.workspace)
    expected = data_root / "jobs" / request.job_no
    if not workspace.is_absolute() or workspace != expected or ".." in workspace.parts:
        raise AgentError("INPUT_INVALID", "Workspace must match the job under DATA_ROOT/jobs")
    ensure_contained(data_root, workspace)
    try:
        checksum_path = workspace / "input-checksums.json"
        ensure_contained(workspace, checksum_path)
        record = json.loads(checksum_path.read_text(encoding="utf-8"))
        files = record.get("files")
        if not isinstance(files, dict) or not files or "manifest.json" not in files:
            raise AgentError("INPUT_INVALID", "Snapshot checksum file map is invalid")
        actual: dict[str, str] = {}
        inputs = workspace / "input"
        ensure_contained(workspace, inputs)
        for name, checksum in files.items():
            relative = Path(name)
            if (
                not isinstance(name, str)
                or not name
                or relative.is_absolute()
                or any(part in (".", "..") for part in name.split("/"))
                or "\\" in name
                or ":" in name
                or "\0" in name
            ):
                raise AgentError("INPUT_INVALID", "Snapshot contains an unsafe relative path")
            path = inputs / relative
            ensure_contained(inputs, path)
            if not path.is_file() or sha256_file(path) != checksum:
                raise AgentError("INPUT_INVALID", "Immutable snapshot file checksum does not match")
            actual[name] = checksum
        snapshot_paths = list(inputs.rglob("*"))
        if any(path.is_symlink() for path in snapshot_paths):
            raise AgentError("INPUT_INVALID", "Symbolic links are not allowed in snapshots")
        if {str(p.relative_to(inputs)) for p in snapshot_paths if not p.is_dir()} != set(files):
            raise AgentError("INPUT_INVALID", "Snapshot contains unregistered files")
        aggregate = aggregate_checksum(actual)
        if aggregate != request.snapshot_checksum or aggregate != record.get("checksum"):
            raise AgentError(
                "INPUT_INVALID", "Immutable snapshot aggregate checksum does not match"
            )
        manifest = json.loads((inputs / "manifest.json").read_text(encoding="utf-8"))
        if (
            manifest.get("analysisType") != request.analysis_type
            or manifest.get("conditionCode") != request.condition_code
        ):
            raise AgentError(
                "INPUT_INVALID", "Snapshot analysis or condition does not match request"
            )
        parameters = manifest.get("parameters", {})
        if not isinstance(parameters, dict):
            raise AgentError("INPUT_INVALID", "Synthetic case parameters must be an object")
        scale = parameters.get("thicknessScale", 1)
        if (
            isinstance(scale, bool)
            or not isinstance(scale, int | float)
            or not math.isfinite(scale)
            or not 0 < scale <= 10
        ):
            raise AgentError(
                "INPUT_INVALID", "Synthetic thicknessScale must be finite and positive"
            )
        seed = parameters.get("seed", 42)
        subcase = parameters.get("subcase", 1)
        if type(seed) is not int or not 0 <= seed <= 2**32 - 1:
            raise AgentError("INPUT_INVALID", "Synthetic seed must be an unsigned 32-bit integer")
        if type(subcase) is not int or not 1 <= subcase <= 1000:
            raise AgentError("INPUT_INVALID", "Synthetic subcase must be an integer from 1 to 1000")
        return workspace, manifest
    except (OSError, ValueError, TypeError, AttributeError) as error:
        raise AgentError("INPUT_INVALID", "Input snapshot could not be verified") from error
