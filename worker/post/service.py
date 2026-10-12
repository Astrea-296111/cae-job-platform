"""Fenced file publication makes a lost HTTP response safe to replay after restart."""

import fcntl
import hashlib
import io
import json
import os
import shutil
import tempfile
import threading
import time
from pathlib import Path

from PIL import Image
from pptx import Presentation

from agents.files import ensure_contained
from app.errors import AgentError
from post.processor import MockHyperViewPostProcessor
from post.report import PptxDraftReportGenerator
from post.source import fail, load_json, read_bytes, verify_source


def fingerprint(payload):
    return hashlib.sha256(
        json.dumps(
            payload, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False
        ).encode()
    ).hexdigest()


class RenderService:
    def __init__(self, settings):
        self.settings = settings
        self.processor = MockHyperViewPostProcessor()
        self.generator = PptxDraftReportGenerator()
        self.faults = {"post": 0, "report": 0}
        self.delays = {"post": 0, "report": 0}
        self.fault_jobs = {"post": None, "report": None}
        self.fault_lock = threading.Lock()

    def fault(self, stage, count, delay=0, job_no=None):
        with self.fault_lock:
            self.faults[stage] = count
            self.delays[stage] = delay
            self.fault_jobs[stage] = job_no
        return {"stage": stage, "failuresRemaining": count, "delaySeconds": delay, "jobNo": job_no}

    def render(self, request, stage):
        payload = request.model_dump(by_alias=True, mode="json")
        root = self.settings.data_root
        job = root / "jobs" / request.job_no
        version = request.post_version if stage == "post" else request.report_version
        parent = job / ("derived" if stage == "post" else "reports") / request.parser_version
        output = parent / f"{stage}-v{version}"
        ensure_contained(root, output)
        parent.mkdir(parents=True, exist_ok=True)
        ensure_contained(root, output)
        descriptor = os.open(
            parent / f".{stage}-v{version}.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600
        )
        with os.fdopen(descriptor, "a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            raw = verify_source(self.settings, request)
            expected = fingerprint(payload)
            if output.exists():
                return self._replay(output, request, stage, expected)
            with self.fault_lock:
                applies = self.fault_jobs[stage] is None or self.fault_jobs[stage] == request.job_no
                delay = self.delays[stage] if applies else 0
                if applies and self.faults[stage] > 0:
                    self.faults[stage] -= 1
                    raise AgentError(
                        "MOCK_RENDER_FAILURE", "Injected synthetic render failure", 503
                    )
            if delay:
                time.sleep(delay)
            temporary = Path(tempfile.mkdtemp(prefix=f".{stage}-", dir=parent))
            try:
                extra = {}
                if stage == "post":
                    self.processor.process(request, raw, temporary)
                    items = []
                    for spec in request.specs:
                        metadata = spec.model_dump(by_alias=True)
                        metadata["synthetic"] = True
                        items.append(
                            self._descriptor(
                                temporary / spec.output_name,
                                output / spec.output_name,
                                "IMAGE",
                                "image/png",
                                metadata,
                            )
                        )
                else:
                    post = (
                        job / "derived" / request.parser_version / f"post-v{request.post_version}"
                    )
                    manifest, checksum = load_json(root, post / "post-manifest.json")
                    if (
                        checksum != request.post_manifest_checksum
                        or manifest.get("requestFingerprint") != request.post_request_fingerprint
                    ):
                        fail("Selected post manifest mismatch")
                    source = manifest.get("request", {})
                    common = set(payload) - {
                        "reportVersion",
                        "templateCode",
                        "templateVersion",
                        "generatedAt",
                        "postManifestChecksum",
                        "postRequestFingerprint",
                    }
                    if any(source.get(key) != payload[key] for key in common):
                        fail("Report source differs from selected post context")
                    images = self._verify_outputs(post, manifest, "post")
                    slides = self.generator.generate(request, images, temporary / "report.pptx")
                    extra = {
                        "reportStatus": "DRAFT",
                        "slideCount": slides,
                        "imageChecksums": {
                            name: hashlib.sha256(data).hexdigest() for name, data in images.items()
                        },
                    }
                    items = [
                        self._descriptor(
                            temporary / "report.pptx",
                            output / "report.pptx",
                            "REPORT_PPTX",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            {
                                "conditionCode": request.condition_code,
                                "subcase": request.subcase,
                                **extra,
                            },
                        )
                    ]
                for path in temporary.iterdir():
                    with path.open("rb") as stream:
                        os.fsync(stream.fileno())
                manifest = {
                    "request": payload,
                    "requestFingerprint": expected,
                    "status": "COMPLETE",
                    "artifacts": items,
                    **extra,
                }
                name = f"{stage}-manifest.json"
                with (temporary / name).open("x", encoding="utf-8") as stream:
                    json.dump(manifest, stream, sort_keys=True, ensure_ascii=False, allow_nan=False)
                    stream.flush()
                    os.fsync(stream.fileno())
                self._verify_outputs(temporary, manifest, stage)
                if output.exists():
                    raise AgentError(
                        "IDEMPOTENCY_CONFLICT", "Stage output appeared during publication", 409
                    )
                os.rename(temporary, output)
                directory = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
            finally:
                if temporary.exists():
                    shutil.rmtree(temporary)
            return self._replay(output, request, stage, expected)

    def _descriptor(self, source, published, artifact_type, content_type, metadata):
        data = read_bytes(self.settings.data_root, source)
        return {
            "artifactType": artifact_type,
            "fileName": published.name,
            "relativePath": str(published.relative_to(self.settings.data_root)),
            "sizeBytes": len(data),
            "checksum": hashlib.sha256(data).hexdigest(),
            "contentType": content_type,
            "metadata": metadata,
        }

    def _verify_outputs(self, directory, manifest, stage):
        expected = (
            {"contour.png", "mode-shape.png", "comparison.png", "curve.png"}
            if stage == "post"
            else {"report.pptx"}
        )
        items = manifest.get("artifacts", [])
        if {row.get("fileName") for row in items} != expected or len(items) != len(expected):
            fail("Stage artifact set is invalid")
        actual = {path.name for path in directory.iterdir()}
        if actual not in (expected, expected | {f"{stage}-manifest.json"}):
            fail("Unexpected immutable stage files")
        contents = {}
        for row in items:
            data = read_bytes(self.settings.data_root, directory / row["fileName"])
            if len(data) != row["sizeBytes"] or hashlib.sha256(data).hexdigest() != row["checksum"]:
                fail("Immutable stage artifact checksum mismatch")
            if stage == "post":
                with Image.open(io.BytesIO(data)) as image:
                    if (
                        image.format != "PNG"
                        or image.width < 400
                        or image.height < 300
                        or image.width * image.height > 8_000_000
                    ):
                        fail("PNG dimensions are invalid")
                    image.verify()
            else:
                presentation = Presentation(io.BytesIO(data))
                if len(presentation.slides) < 4 or manifest.get("reportStatus") != "DRAFT":
                    fail("Draft PPTX is incomplete")
            contents[row["fileName"]] = data
        return contents

    def _replay(self, output, request, stage, expected):
        manifest, _ = load_json(self.settings.data_root, output / f"{stage}-manifest.json")
        if (
            manifest.get("requestFingerprint") != expected
            or fingerprint(manifest.get("request")) != expected
        ):
            raise AgentError(
                "IDEMPOTENCY_CONFLICT", "Stage version belongs to different frozen inputs", 409
            )
        self._verify_outputs(output, manifest, stage)
        payload = request.model_dump(by_alias=True, mode="json")
        keys = (
            "jobNo",
            "executionId",
            "executionRequestId",
            "parseRecordId",
            "parserVersion",
            "postVersion",
            "conditionCode",
            "subcase",
            "rawChecksum",
        )
        result = {key: payload[key] for key in keys}
        result.update(
            {
                "schemaVersion": 1,
                "status": "COMPLETE",
                "requestFingerprint": expected,
                "artifacts": manifest["artifacts"],
                "manifest": self._descriptor(
                    output / f"{stage}-manifest.json",
                    output / f"{stage}-manifest.json",
                    "POST_MANIFEST" if stage == "post" else "REPORT_MANIFEST",
                    "application/json",
                    {"conditionCode": request.condition_code, "subcase": request.subcase},
                ),
            }
        )
        if stage == "report":
            result.update(
                {key: manifest[key] for key in ("reportStatus", "slideCount", "imageChecksums")}
            )
            result["reportVersion"] = request.report_version
        return result
