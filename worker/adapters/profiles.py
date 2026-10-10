"""Explicit profile whitelist; no user shell commands or executable paths."""

from app.contracts import SubmitRequest
from app.errors import AgentError
from app.settings import Settings

PROFILES = {f"{analysis}_STANDARD": analysis for analysis in ("STRUCTURE", "NVH", "CRASH", "CFD")}


def validate_profile(request: SubmitRequest, settings: Settings) -> None:
    if PROFILES.get(request.solver_profile) != request.analysis_type:
        raise AgentError("SUBMIT_REJECTED", "Unknown or incompatible solver profile")
    if request.analysis_type not in settings.solver_tags:
        raise AgentError("SUBMIT_REJECTED", "Node does not support the requested analysis")
    if (
        request.resource.cpu > settings.cpu_total
        or request.resource.memory_gb > settings.memory_total_gb
    ):
        raise AgentError("SUBMIT_REJECTED", "Requested resources exceed node capacity")
