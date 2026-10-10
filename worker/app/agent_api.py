"""Service-token protected execution API; ACK never waits for Solver completion."""

import asyncio

from fastapi import APIRouter, Depends, Header, Request

from app.contracts import HeartbeatControl, SubmitRequest
from app.errors import AgentError

router = APIRouter(prefix="/worker/v1")


def require_service_token(request: Request, x_service_token: str | None = Header(default=None)):
    import hmac

    expected = request.app.state.settings.service_token
    if x_service_token is None or not hmac.compare_digest(x_service_token, expected):
        raise AgentError("UNAUTHENTICATED", "A valid service token is required", 401)


@router.post("/jobs/submit", dependencies=[Depends(require_service_token)], status_code=202)
async def submit(payload: SubmitRequest, request: Request):
    state, claimed = await asyncio.to_thread(request.app.state.manager.submit, payload)
    if claimed and payload.mock.response_delay_once_seconds:
        await asyncio.sleep(payload.mock.response_delay_once_seconds)
    return {
        "externalExecutionId": state["externalExecutionId"],
        "accepted": True,
        "state": state["state"],
    }


@router.get("/jobs/by-request/{request_id}", dependencies=[Depends(require_service_token)])
def by_request(request_id: str, request: Request):
    return request.app.state.manager.query_by_request(request_id)


@router.get("/jobs/{external_id}", dependencies=[Depends(require_service_token)])
def query(external_id: str, request: Request):
    return request.app.state.manager.query(external_id)


@router.post("/jobs/{external_id}/cancel", dependencies=[Depends(require_service_token)])
def cancel(external_id: str, request: Request):
    return request.app.state.manager.cancel(external_id)


@router.get("/node/status", dependencies=[Depends(require_service_token)])
def node_status(request: Request):
    return request.app.state.manager.node_status()


@router.post("/faults/heartbeat", dependencies=[Depends(require_service_token)])
def heartbeat_fault(payload: HeartbeatControl, request: Request):
    request.app.state.heartbeat_control["enabled"] = payload.enabled
    return {"enabled": payload.enabled}
