"""Small asynchronous heartbeat task; offline control does not stop running solvers."""

import asyncio
import logging

import httpx

from agents.execution_manager import ExecutionManager

logger = logging.getLogger(__name__)


async def heartbeat_loop(manager: ExecutionManager, control: dict[str, bool]) -> None:
    settings = manager.settings
    async with httpx.AsyncClient(timeout=3, trust_env=False) as client:
        while True:
            if control["enabled"] and settings.backend_url:
                try:
                    response = await client.post(
                        f"{settings.backend_url.rstrip('/')}/internal/v1/agents/{settings.node_id}/heartbeat",
                        json=await asyncio.to_thread(manager.node_status),
                        headers={"X-Service-Token": settings.service_token},
                    )
                    response.raise_for_status()
                except httpx.HTTPError as error:
                    logger.warning(
                        "nodeId=%s heartbeat unavailable: %s",
                        settings.node_id,
                        type(error).__name__,
                    )
            await asyncio.sleep(settings.heartbeat_interval)
