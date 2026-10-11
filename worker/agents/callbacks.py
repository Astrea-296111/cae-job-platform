"""Durable runtime callbacks complement polling without controlling solver execution."""

import asyncio
import hashlib
import json
import logging
import time
import uuid

import httpx

from agents.execution_manager import ExecutionManager
from agents.process_registry import TERMINAL

logger = logging.getLogger(__name__)
CALLBACK_FIELDS = (
    "executionRequestId",
    "externalExecutionId",
    "state",
    "progress",
    "pid",
    "processIdentity",
    "exitCode",
    "rawSolverCode",
    "rawMessage",
    "startedAt",
    "finishedAt",
)
PAGE_SIZE = 32
MAX_CONCURRENCY = 4


class CallbackNotifier:
    """Bounded keyset scans and SQLite acknowledgements avoid unbounded process caches."""

    def __init__(self, manager: ExecutionManager):
        self.manager = manager
        self.generation = uuid.uuid4().hex
        self.cursors = {False: "", True: ""}
        self._slots = asyncio.Semaphore(MAX_CONCURRENCY)

    async def deliver(self, client: httpx.AsyncClient, row: dict) -> None:
        async with self._slots:
            try:
                await self._deliver(client, row)
            except Exception as error:
                # A malformed local record must not suppress notifications for other executions.
                logger.warning(
                    "nodeId=%s executionRequestId=%s callback deferred: %s",
                    self.manager.settings.node_id,
                    row["request_id"],
                    type(error).__name__,
                )

    async def _deliver(self, client: httpx.AsyncClient, row: dict) -> None:
        runtime = await asyncio.to_thread(self.manager.query, row["external_id"])
        payload = {field: runtime.get(field) for field in CALLBACK_FIELDS}
        fingerprint = hashlib.sha256(
            json.dumps(payload, sort_keys=True, allow_nan=False).encode()
        ).hexdigest()
        if row["delivered_fingerprint"] == fingerprint and (
            runtime["state"] not in TERMINAL or row["delivered_generation"] == self.generation
        ):
            return
        same_attempt = row["attempt_fingerprint"] == fingerprint
        if same_attempt and (row["next_attempt_at"] or 0) > time.time():
            return
        settings = self.manager.settings
        try:
            response = await client.post(
                f"{settings.backend_url.rstrip('/')}/internal/v1/agents/"
                f"{settings.node_id}/executions/callback",
                json=payload,
                headers={"X-Service-Token": settings.service_token},
            )
            response.raise_for_status()
            acknowledgement = response.json()
            if not isinstance(acknowledgement, dict) or acknowledgement.get("accepted") is not True:
                raise ValueError("Backend did not acknowledge runtime facts")
        except (httpx.HTTPError, ValueError) as error:
            attempts = min(16, (row["attempts"] or 0) + 1) if same_attempt else 1
            retry_after = min(30, 0.5 * 2 ** (attempts - 1))
            await asyncio.to_thread(
                self.manager.registry.callback_failed,
                row["request_id"],
                fingerprint,
                attempts,
                time.time() + retry_after,
            )
            logger.warning(
                "nodeId=%s executionRequestId=%s state=%s callback unavailable: %s; retry=%ss",
                settings.node_id,
                row["request_id"],
                runtime["state"],
                type(error).__name__,
                retry_after,
            )
            return
        await asyncio.to_thread(
            self.manager.registry.callback_delivered,
            row["request_id"],
            fingerprint,
            self.generation,
            runtime["state"],
        )

    async def scan(self, client: httpx.AsyncClient) -> None:
        # Active progress stays prompt even when startup must replay a large terminal archive.
        for terminal in (False, True):
            rows = await asyncio.to_thread(
                self.manager.registry.callback_candidates,
                self.cursors[terminal],
                self.generation,
                PAGE_SIZE,
                terminal,
            )
            if not rows:
                self.cursors[terminal] = ""
                continue
            self.cursors[terminal] = rows[-1]["request_id"]
            await asyncio.gather(*(self.deliver(client, row) for row in rows))


async def callback_loop(manager: ExecutionManager) -> None:
    """Dedicated bounded HTTP loop; failure does not block heartbeat or Agent API requests."""
    notifier = CallbackNotifier(manager)
    async with httpx.AsyncClient(
        timeout=3,
        trust_env=False,
        follow_redirects=False,
        limits=httpx.Limits(
            max_connections=MAX_CONCURRENCY, max_keepalive_connections=MAX_CONCURRENCY
        ),
    ) as client:
        while True:
            if manager.settings.backend_url:
                try:
                    await notifier.scan(client)
                except Exception as error:
                    logger.warning(
                        "nodeId=%s callback scan deferred: %s",
                        manager.settings.node_id,
                        type(error).__name__,
                    )
            await asyncio.sleep(manager.settings.callback_interval)
