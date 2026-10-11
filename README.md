# CAE Job Platform

Public reproduction of a CAE job control plane and result/report pipeline.
Synthetic cases and mock solver processes only; no commercial solver, enterprise data or license is required.

Milestones 0–6 are validated: all Compose services start; MySQL Job APIs, state/event CAS, role checks, safe Case validation and immutable input snapshots work. Three Agents run independent mock Solver processes with durable idempotency, query, cancellation and heartbeats. Backend scheduling atomically reserves CPU, memory and license tokens, persists execution attempts and confirms terminal evidence before releasing capacity. RabbitMQ submission uses a transactional Outbox, publisher confirms, manual ACK, durable Inbox idempotency and delayed retries/DLQ. Authenticated callbacks and leased reconciliation recover the same execution after backend restart or missing heartbeats, preserve UNKNOWN capacity, and dispatch successful results once to the real parse queue. Result parsing and report generation are the next milestones. See [progress](docs/progress.md) and [reconciliation validation](docs/validation/m6-validation.md).

## Development

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

Demo credentials (local demonstration only): `admin/admin123`, `engineer/engineer123`, `lead/lead123`.

Cloud builds use the existing proxy and public CA without disabling TLS verification:

```bash
bash scripts/configure-cloud-build.sh
DOCKER_CONFIG=.local/docker docker compose -f deploy/docker-compose.yml up -d --build
```

Health endpoints: backend `/actuator/health`, agents `/health`, frontend `/health`.
