"""FastAPI entry point for the node agent."""

import asyncio
import contextlib
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from agents.callbacks import callback_loop
from agents.execution_manager import ExecutionManager
from agents.heartbeat import heartbeat_loop
from app.agent_api import router
from app.errors import AgentError
from app.settings import Settings
from post.api import router as render_router
from post.service import RenderService


def create_app(settings: Settings | None = None) -> FastAPI:
    """Create an application without running a solver at import time."""
    settings = settings or Settings.from_environment()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        application.state.settings = settings
        application.state.manager = ExecutionManager(settings)
        application.state.render_service = RenderService(settings)
        application.state.render_slots = asyncio.Semaphore(2)
        application.state.heartbeat_control = {"enabled": True}
        application.state.heartbeat_task = asyncio.create_task(
            heartbeat_loop(application.state.manager, application.state.heartbeat_control),
            name="agent-heartbeat",
        )
        application.state.callback_task = asyncio.create_task(
            callback_loop(application.state.manager), name="agent-execution-callbacks"
        )
        try:
            yield
        finally:
            tasks = (application.state.heartbeat_task, application.state.callback_task)
            for task in tasks:
                task.cancel()
            for task in tasks:
                with contextlib.suppress(asyncio.CancelledError):
                    await task

    application = FastAPI(
        title="CAE Compute Agent",
        version="0.1.0",
        description="Synthetic CAE execution agent; no commercial solver is required.",
        lifespan=lifespan,
    )

    application.include_router(router)
    application.include_router(render_router)

    @application.exception_handler(AgentError)
    async def agent_error(_request: Request, error: AgentError):
        return JSONResponse(
            status_code=error.status,
            content={"success": False, "code": error.code, "message": error.message, "details": {}},
        )

    @application.get("/health", tags=["health"])
    def health() -> dict[str, str]:
        return {"status": "OK", "nodeId": settings.node_id}

    return application


app = create_app()
