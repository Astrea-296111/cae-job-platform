# CAE Job Platform

Public reproduction of a CAE job control plane and result/report pipeline.
Synthetic cases and mock solver processes only; no commercial solver, enterprise data or license is required.

Milestones 0–7 are validated: all Compose services start; MySQL Job APIs, state/event CAS, role checks, safe Case validation and immutable input snapshots work. Three Agents run independent mock Solver processes with durable idempotency, query, cancellation and heartbeats. Backend scheduling atomically reserves CPU, memory and license tokens, persists execution attempts and confirms terminal evidence before releasing capacity. RabbitMQ submission uses a transactional Outbox, publisher confirms, manual ACK, durable Inbox idempotency and delayed retries/DLQ. Authenticated callbacks and leased reconciliation recover the same execution after backend restart or missing heartbeats and preserve UNKNOWN capacity.

The real parse consumer stores seven structural metrics, including four modal frequencies, and NVH/CRASH/CFD pilot metrics. Nine public Case packages include `BIW_MODAL` subcase 2. Parser v1/v2 retain independent metric records and immutable JSON/CSV exports; concurrent reparsing reuses the original Solver execution. Project-authorized downloads verify registered file bytes and SHA before streaming. Successful parsing dispatches a durable post-processing command; PNG and PPTX generation are the next milestone. See [progress](docs/progress.md), [parser API and guarantees](docs/result-parsing.md), and [live validation](docs/validation/m7-validation.md).

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
