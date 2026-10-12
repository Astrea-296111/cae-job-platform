# CAE Job Platform

Public reproduction of a CAE job control plane and result/report pipeline.
Synthetic cases and mock solver processes only; no commercial solver, enterprise data or license is required.

Milestones 0–9 are validated: all Compose services start; MySQL Job APIs, state/event CAS, role checks, safe Case validation and immutable input snapshots work. Three Agents run independent mock Solver processes with durable idempotency, query, cancellation and heartbeats. Backend scheduling atomically reserves CPU, memory and license tokens, persists execution attempts and confirms terminal evidence before releasing capacity. RabbitMQ submission uses a transactional Outbox, publisher confirms, manual ACK, durable Inbox idempotency and delayed retries/DLQ. Authenticated callbacks and leased reconciliation recover the same execution after backend restart or missing heartbeats and preserve UNKNOWN capacity.

The real parse consumer stores seven structural metrics, including four modal frequencies, and NVH/CRASH/CFD pilot metrics. Nine public Case packages include `BIW_MODAL` subcase 2. Parser v1/v2 retain independent metric records and immutable JSON/CSV exports; concurrent reparsing reuses the original Solver execution. Project-authorized downloads verify registered file bytes and SHA before streaming. Successful parsing dispatches durable post processing. Four actual synthetic PNGs and five-slide PPTX drafts retain the selected parser version, condition and Subcase. Java calculates historical comparisons; versioned reports support manual Lead review/Admin approval. Stage-only retry and backend restart reuse the original Solver and immutable output files. The Vue workbench supports authenticated login, real dashboard aggregates, Case/profile selection, filtered Job lists, execution/log/metric/image/report details, SSE notifications, node drain/profile editing and role-authorized report review. Creation retries preserve a durable idempotency key; time displays use Asia/Shanghai. See [progress](docs/progress.md), [parser API](docs/result-parsing.md), [post/report guarantees and API](docs/post-and-reports.md), [report validation](docs/validation/m8-validation.md), and [workbench/SSE](docs/frontend.md).

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

Workstation: `http://localhost:8088`. Run `scripts/check.sh` for all current checks and affected image builds; run `scripts/test-ui.sh` for real Chromium/Nginx acceptance. See [browser evidence](docs/validation/m9-ui-smoke.json).
