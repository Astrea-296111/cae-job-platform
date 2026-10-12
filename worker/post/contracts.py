"""Strict control-plane rendering contracts; callers supply identities, never paths."""

from decimal import Decimal
from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.contracts import Contract

Identifier = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,100}$")]
Checksum = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
DecimalString = Annotated[str, Field(pattern=r"^-?\d{1,100}(\.\d{1,10})?$", max_length=128)]


class Metric(Contract):
    metric_code: str = Field(alias="metricCode", pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    metric_name: str = Field(alias="metricName", min_length=1, max_length=160)
    value_num: DecimalString | None = Field(alias="valueNum")
    value_text: str | None = Field(alias="valueText", max_length=500)
    unit: str = Field(max_length=64)
    dimension: str = Field(max_length=64)
    status: str = Field(max_length=32)
    condition_code: Identifier = Field(alias="conditionCode")
    subcase: int = Field(ge=1, le=1000)

    @model_validator(mode="after")
    def value_type(self):
        if (self.value_num is None) == (self.value_text is None):
            raise ValueError("Exactly one metric value is required")
        if self.value_num is not None and abs(Decimal(self.value_num)) >= Decimal("1e20"):
            raise ValueError("Metric exceeds durable numeric capacity")
        return self


class Comparison(Contract):
    metric_code: Identifier = Field(alias="metricCode")
    baseline: DecimalString | None
    current: DecimalString | None
    delta: DecimalString | None
    delta_percent: DecimalString | None = Field(alias="deltaPercent")
    unit: str = Field(max_length=64)
    status: Literal["MATCHED", "NO_BASELINE", "ZERO_BASELINE", "TEXT", "MISSING"]


class Context(Contract):
    project_code: str = Field(alias="projectCode", max_length=128)
    project_name: str = Field(alias="projectName", max_length=128)
    model_code: str = Field(alias="modelCode", max_length=128)
    model_name: str = Field(alias="modelName", max_length=128)
    model_revision: str = Field(alias="modelRevision", max_length=128)
    maturity: Literal["COMPLETE", "PILOT"]


class BaselineRef(Contract):
    job_no: str = Field(alias="jobNo", pattern=r"^CAE-\d{8}-\d{6,}$")
    parse_record_id: int = Field(alias="parseRecordId", gt=0)


class PostProcessSpec(Contract):
    condition_code: Identifier = Field(alias="conditionCode")
    result_type: Literal["CONTOUR", "MODE_SHAPE", "COMPARISON", "CURVE"] = Field(alias="resultType")
    component: Literal["SYNTHETIC_BODY"]
    subcase: int = Field(ge=1, le=1000)
    frame: Literal[0]
    view: Literal["ISOMETRIC"]
    selection_set: Literal["PUBLIC_DEMO"] = Field(alias="selectionSet")
    output_name: Literal["contour.png", "mode-shape.png", "comparison.png", "curve.png"] = Field(
        alias="outputName"
    )


class RenderSource(Contract):
    schema_version: Literal[1] = Field(alias="schemaVersion")
    job_no: str = Field(alias="jobNo", pattern=r"^CAE-\d{8}-\d{6,}$")
    execution_id: int = Field(alias="executionId", gt=0)
    execution_request_id: Identifier = Field(alias="executionRequestId")
    external_execution_id: Identifier = Field(alias="externalExecutionId")
    node_id: Identifier = Field(alias="nodeId")
    pid: int = Field(gt=0)
    process_identity: str = Field(alias="processIdentity", max_length=100)
    parse_record_id: int = Field(alias="parseRecordId", gt=0)
    parser_version: Literal["v1", "v2"] = Field(alias="parserVersion")
    post_version: int = Field(alias="postVersion", ge=1, le=10000)
    analysis_type: Literal["STRUCTURE", "NVH", "CRASH", "CFD"] = Field(alias="analysisType")
    condition_code: Identifier = Field(alias="conditionCode")
    subcase: int = Field(ge=1, le=1000)
    raw_checksum: Checksum = Field(alias="rawChecksum")
    snapshot_checksum: Checksum = Field(alias="snapshotChecksum")
    result_json_checksum: Checksum = Field(alias="resultJsonChecksum")
    context: Context
    metrics: list[Metric] = Field(min_length=1, max_length=32)
    comparisons: list[Comparison] = Field(max_length=32)
    baseline: BaselineRef | None

    @model_validator(mode="after")
    def consistent(self):
        codes = {m.metric_code for m in self.metrics}
        if (
            len(codes) != len(self.metrics)
            or {m.metric_code for m in self.comparisons} != codes
            or len(self.comparisons) != len(codes)
        ):
            raise ValueError("Metrics/comparisons must form the same unique set")
        for metric in self.metrics:
            if (metric.condition_code, metric.subcase) != (self.condition_code, self.subcase):
                raise ValueError("Metric condition/subcase mismatch")
        indexed = {m.metric_code: m for m in self.metrics}
        for row in self.comparisons:
            metric = indexed[row.metric_code]
            if (
                row.unit != metric.unit
                or (row.current is None) != (metric.value_num is None)
                or (row.current is not None and Decimal(row.current) != Decimal(metric.value_num))
            ):
                raise ValueError("Comparison current value/unit differs from canonical metric")
        return self


class PostRequest(RenderSource):
    specs: list[PostProcessSpec] = Field(min_length=4, max_length=4)

    @model_validator(mode="after")
    def spec_set(self):
        pairs = {
            ("CONTOUR", "contour.png"),
            ("MODE_SHAPE", "mode-shape.png"),
            ("COMPARISON", "comparison.png"),
            ("CURVE", "curve.png"),
        }
        if {(s.result_type, s.output_name) for s in self.specs} != pairs:
            raise ValueError("Four controlled output types are required")
        if any(
            (s.condition_code, s.subcase) != (self.condition_code, self.subcase) for s in self.specs
        ):
            raise ValueError("Post condition/subcase differs from report KPI")
        return self


class ReportRequest(RenderSource):
    report_version: int = Field(alias="reportVersion", ge=1, le=10000)
    template_code: Literal["STRUCTURE_DRAFT", "PILOT_DRAFT"] = Field(alias="templateCode")
    template_version: Literal["v1"] = Field(alias="templateVersion")
    generated_at: str = Field(alias="generatedAt", pattern=r"^\d{4}-\d{2}-\d{2}T.+Z$")
    post_manifest_checksum: Checksum = Field(alias="postManifestChecksum")
    post_request_fingerprint: Checksum = Field(alias="postRequestFingerprint")

    @model_validator(mode="after")
    def template_matches_analysis(self):
        expected = "STRUCTURE_DRAFT" if self.analysis_type == "STRUCTURE" else "PILOT_DRAFT"
        if self.template_code != expected:
            raise ValueError("Template does not match the selected analysis")
        from datetime import datetime

        datetime.fromisoformat(self.generated_at.replace("Z", "+00:00"))
        return self


class RenderFault(Contract):
    stage: Literal["post", "report"]
    failures_remaining: int = Field(alias="failuresRemaining", ge=0, le=100)
    delay_seconds: float = Field(default=0, alias="delaySeconds", ge=0, le=30)
    job_no: str | None = Field(default=None, alias="jobNo", pattern=r"^CAE-\d{8}-\d{6,}$")
