# CAE Job Platform

Public reproduction of a CAE job control plane and result/report pipeline.
Synthetic cases and mock solver processes only; no commercial solver, enterprise data or license is required.

Milestones 0–11 implement the complete public reference path: all Compose services start; MySQL Job APIs, state/event CAS, role checks, safe Case validation and immutable input snapshots work. Three Agents run independent mock Solver processes with durable idempotency, query, cancellation and heartbeats. Backend scheduling atomically reserves CPU, memory and license tokens, persists execution attempts and confirms terminal evidence before releasing capacity. RabbitMQ submission uses a transactional Outbox, publisher confirms, manual ACK, durable Inbox idempotency and delayed retries/DLQ. Authenticated callbacks and leased reconciliation recover the same execution after backend restart or missing heartbeats and preserve UNKNOWN capacity.

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

Batch creation and parent/child progress are available at `/batches`, with durable intent replay, bounded admission, backpressure and per-discipline fair scanning. `make failure-demo` runs all [12 real scenarios](docs/validation/m10-validation.md), including a 40-Job partial-failure batch. [Batch design](docs/design/m10-batch.md).

`make demo` creates V36 baseline, V37 current and modal Subcase 2 Jobs, verifies actual PNG/PPTX content, and prints authorized download URLs. `make benchmark` measures the six required local workloads. The recorded 100-child batch completed with 100 successful reports in 98.583 seconds, zero measured API errors, and no resource overselling. These are [actual local measurements](docs/benchmark.md), not enterprise production KPIs.

Prometheus is at `http://localhost:9090`; Grafana at `http://localhost:3000` with local demo login `admin/demo-grafana-password`. The provisioned dashboard has 13 panels backed by [actual platform metrics](docs/observability.md). Final tests: 347 Java and 60 Python, plus real Chromium acceptance. See [all 17 final deliverables](docs/final-delivery.md), [interview demo](docs/interview-demo.md) and [commercial adapter mapping](docs/real-system-mapping.md).

Docker and Compose are required for deployment. Host development/demo/benchmark requires Node 20.19+ (or 22.12+) and Python 3.11+; `bash scripts/bootstrap.sh` installs pinned Python/frontend dependencies and prepares Java 17/Maven. Flyway migrates and seeds fresh databases automatically. Deployment variables in `deploy/docker-compose.yml` have public demo defaults and support environment overrides. Stop with `make down` to preserve database/results/Agent state volumes.

## Measurement contract and planning targets

See the [metric definitions and verification plan](docs/metrics/README.md), [unmeasured target profile](docs/metrics/targets.example.json), and [resume wording drafts](docs/metrics/resume-drafts.md). The profile contains planning targets and labelled illustrative assumptions, not achieved performance or historical enterprise outcomes. Existing measured results remain in [the benchmark record](docs/benchmark.md). This documentation change does not implement performance optimizations or run the proposed workloads.
