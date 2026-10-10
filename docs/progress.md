# Implementation progress

Specification: `docs/reference/Codex提示词_CAE仿真任务调度结果管理与自动报告平台_完整复现.md`.

## Milestone 0
- [x] Repository layout, Java 17 / Spring Boot 3.3.6, FastAPI and Vue 3 bootstrap.
- [x] Eleven Compose services built/started; MySQL, Redis, RabbitMQ, agents, backend, frontend and monitoring validated.
- [x] Backend `/actuator/health=UP`, three agent `/health=OK`, Nginx frontend responds; Chromium rendered Vue bootstrap.
- Tests: Java 2 passed; Python 2 passed; Spotless/Ruff/ESLint and vue-tsc/Vite pass; all application Docker builds pass; Compose config passes; Prometheus config validation passes.
- Known issues: none blocking bootstrap. Synthetic solver and domain functionality belong to subsequent milestones and are not implemented yet.
- Decisions: modular monolith; independent node agents; shared-data volume; optional BuildKit proxy/CA secrets preserve TLS verification in cloud builds. Runtime source mounts require public config permissions because cloud umask is restrictive.

## Milestone 1
- [x] MySQL Flyway schema, seed projects/models/revisions, Job create/read/filter/event APIs and audit.
- [x] Central transition policy; state and metadata status/version CAS; project and role authorization.
- Tests: Java 38 passed (27 business state cases, 9 real-MySQL integration, 2 bootstrap); Python 2 passed; all format/lint/type/build checks pass. Updated backend Docker build and live create/get/event requests pass.
- Known issues: none blocking M1. Full gate exposed Compose .env path resolution and vfs disk pressure; both diagnosed and corrected. Keep TLS verification and volume data intact.
- Decisions: explicit parameterized SQL repositories (ADR-006); actual MySQL service integration tests rather than skipped container tests.

## Milestone 2
- [x] Case validation, bounded recursive INCLUDE checks, path/symlink safety, immutable input snapshots, SHA-256 integrity and explicit failures.
- [x] Eight reproducible synthetic Case packages and authenticated Case catalog; live valid Case reaches READY and missing INCLUDE reaches VALIDATION_FAILED / INPUT_INCLUDE_MISSING.
- Tests: Java 72 passed, including 30 snapshot safety tests and 4 real-MySQL validation tests; Python 2 passed; all format/lint/type/build checks pass. Updated backend image built and started healthy.
- Known issues: none blocking M2. Docker vfs storage requires selective application builds and bounded removal of unused build caches; persistent volumes are preserved.
- Decisions: validation freezes the exact checked input into a Job-owned workspace before execution; agents will verify the same checksum before starting a process.

## Milestone 3
- [x] SQLite durable executionRequestId/fingerprint claims, independent mock Solver processes for all four disciplines, query/by-request and identity-safe cancellation.
- [x] Snapshot verification, profile whitelist, local atomic capacity defense, authenticated five-second heartbeats and heartbeat failure control.
- Tests: Java 105 passed; Python 32 passed; all format/lint/type/build checks pass. Backend and Worker images rebuilt; three Agents and backend started healthy. Live duplicate-submit, accepted-response-timeout/retry and running-cancel checks passed; all three actual heartbeats received. Evidence: `docs/validation/m3-agent-smoke.json`.
- Known issues: none blocking M3. Backend scheduling and durable node/resource accounting belong to M4; the Agent-only smoke deliberately leaves backend Jobs READY.
- Decisions: ADR-003 records the non-atomic SQLite-claim/process-launch window and conservative UNKNOWN handling; ADR-005 records shared filesystem boundaries. No exactly-once delivery claim.

## Milestone 4
- [ ] Persistent node registry, scheduling, license/resource reservation and submission orchestration.
- Tests: not yet run.
- Known issues: none established.
