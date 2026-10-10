"""FastAPI entry point for the node agent."""

import os

from fastapi import FastAPI


def create_app() -> FastAPI:
    """Create an application without running a solver at import time."""
    application = FastAPI(
        title="CAE Compute Agent",
        version="0.1.0",
        description="Synthetic CAE execution agent; no commercial solver is required.",
    )

    @application.get("/health", tags=["health"])
    def health() -> dict[str, str]:
        return {"status": "OK", "nodeId": os.getenv("NODE_ID", "node-a")}

    return application


app = create_app()
