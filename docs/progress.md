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
- [x] Durable three-node registry, Redis observation cache, hard filters/weighted scoring, atomic CPU/memory/license reservations and administrative resource profiles.
- [x] Idempotent submit/retry commands, immutable execution attempts, conservative transport retries, confirmed remote cancellation and automatic WAITING_RESOURCE scans.
- Tests: Java 152 passed; Python 36 passed; all format/lint/type/build checks pass. Backend and Worker images rebuilt and started healthy. Live eight concurrent duplicate commands produced one execution/PID; confirmed running cancellation restored its node and license counters. All three nodes ONLINE/reachable. Evidence: `docs/validation/m4-scheduler-smoke.json` and `docs/validation/m4-validation.md`.
- Known issues: none blocking M4. Successful Solver completion remains backend SUBMITTED with capacity held until the M6 monitor; this is explicitly checked rather than presented as a completed pipeline. Old M1 test fixtures contained seven fabricated execution references; backed up and audited their targeted repair before applying V2/V3. Tests now use owned schemas/cache namespaces.
- Decisions: ADR-007 specifies MySQL as admission truth and Job → license → node → reservation locking. Image builds retain a measured 6 GiB reserve for Java and 4 GiB for the smaller Worker/frontend builds under Docker vfs; only unused caches/dangling images are pruned.

## Milestone 5
- [ ] RabbitMQ durable commands, transactional Outbox, publisher confirms, manual ACK, Inbox idempotency and bounded retries/DLQ.
- Tests: not yet run.
- Known issues: none established.
