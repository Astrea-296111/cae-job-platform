"""Rendering stays off the event loop; the service token does not grant report approval."""

import asyncio

from fastapi import APIRouter, Depends, Request

from app.agent_api import require_service_token
from post.contracts import PostRequest, RenderFault, ReportRequest

router = APIRouter(prefix="/worker/v1", dependencies=[Depends(require_service_token)])


async def render(request, payload, stage):
    async with request.app.state.render_slots:
        return await asyncio.to_thread(request.app.state.render_service.render, payload, stage)


@router.post("/post/process")
async def post(payload: PostRequest, request: Request):
    return await render(request, payload, "post")


@router.post("/reports/generate")
async def report(payload: ReportRequest, request: Request):
    return await render(request, payload, "report")


@router.post("/faults/render")
def fault(payload: RenderFault, request: Request):
    return request.app.state.render_service.fault(
        payload.stage, payload.failures_remaining, payload.delay_seconds, payload.job_no
    )
