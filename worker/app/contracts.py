"""Explicit Agent wire contracts; unknown inputs cannot become process arguments."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class Contract(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class Resource(Contract):
    cpu: int = Field(default=8, ge=1, le=128)
    memory_gb: float = Field(default=16, alias="memoryGb", gt=0, le=512)
    walltime_min: float = Field(default=30, alias="walltimeMin", gt=0, le=240)


class MockOptions(Contract):
    runtime_seconds: float = Field(default=3, alias="runtimeSeconds", ge=0.05, le=600)
    success_probability: float = Field(default=1, alias="successProbability", ge=0, le=1)
    failure_mode: Literal["NONE", "RUNTIME_FAILED", "TIMEOUT"] = Field(
        default="NONE", alias="failureMode"
    )
    progress_interval: float = Field(default=0.2, alias="progressInterval", ge=0.02, le=10)
    response_delay_once_seconds: float = Field(
        default=0, alias="responseDelayOnceSeconds", ge=0, le=30
    )
    seed: int | None = Field(default=None, ge=0, le=2**32 - 1)


class SubmitRequest(Contract):
    execution_request_id: str = Field(alias="executionRequestId", pattern=r"^[A-Za-z0-9_-]{1,100}$")
    job_no: str = Field(alias="jobNo", pattern=r"^CAE-\d{8}-\d{6,}$")
    workspace: str = Field(min_length=1, max_length=1024)
    solver_profile: str = Field(alias="solverProfile", min_length=1, max_length=64)
    analysis_type: Literal["STRUCTURE", "NVH", "CRASH", "CFD"] = Field(alias="analysisType")
    condition_code: str = Field(alias="conditionCode", pattern=r"^[A-Za-z0-9_-]{1,64}$")
    snapshot_checksum: str = Field(alias="snapshotChecksum", pattern=r"^[a-f0-9]{64}$")
    resource: Resource = Field(default_factory=Resource)
    mock: MockOptions = Field(default_factory=MockOptions)


class HeartbeatControl(Contract):
    enabled: bool
