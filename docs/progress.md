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
- [x] MySQL transactional Outbox, leased SKIP LOCKED publisher, correlated confirm/mandatory-return validation, persistent RabbitMQ queues and manual ACK.
- [x] Inbox logical-event leases/deduplication, delayed same-execution retries, bounded business delivery/DLQ and conservative UNKNOWN on uncertain exhaustion. Parse/post/report queues are declared without placeholder consumers.
- Tests: Java 163 passed, including 10 new real-MySQL/Rabbit integration tests and the acceptance/cancel-failure regression; Python 36 passed; all format/lint/type/build checks pass. Backend image rebuilt and deployed healthy. Real broker smoke proves eight duplicate commands produce one execution/PID, Inbox DONE and broker ACK while Solver runs (1.15s command-to-DONE in this demo), a real accepted-response timeout retries the same immutable request/PID, and an invalid version enters the actual DLQ with broker x-death metadata. Owned smoke reservations return to their prior baseline. Evidence: `docs/validation/m5-messaging-smoke.json`.
- Known issues: none blocking M5. The first gate exposed concurrent INSERT IGNORE lock-upgrade deadlock; fixed with a no-op upsert taking the exclusive row lock directly. Database outages retain unacked deliveries with capped recovery backoff. M6 will monitor normal execution completion and backfill the M5 smoke's confirmed SUCCESS attempts into the parser queue.
- Decisions: ADR-004 documents at-least-once delivery plus idempotent business handling, not exactly-once. Tests own separate MySQL/Redis/Rabbit namespaces. Docker vfs fallback may remove only the unused pinned bootstrap Maven extraction image after checking no container uses it; all application/runtime images, containers and volumes are preserved.

## Milestone 6
- [x] Fenced MySQL poll leases, authenticated durable callbacks, identity-checked monotonic observations and persistent cancellation intent.
- [x] UNKNOWN/offline recovery of the original execution, confirmed terminal resource release, unique transactional parse dispatch and legacy terminal repair.
- Tests: Java 180 passed, including 17 new real-MySQL/HTTP monitoring tests; Python 42 passed, including six actual-process/HTTP callback tests; all format/lint/frontend build checks pass. Backend and Worker images rebuilt and deployed healthy. Five live scenarios passed: backend-only restart during a 40-second Solver, heartbeat OFFLINE/UNKNOWN with active callbacks and recovery of the original 60-second Solver, runtime failure, timeout and cancellation/completion race. Successful executions each retain one SENT parse command and one real queued message; license counters return to baseline and the M5 DLQ evidence is preserved. Evidence: `docs/validation/m6-reconciliation-smoke.json` and `docs/validation/m6-validation.md`.
- Known issues: none blocking M6. Final review found a RUNNING → UNKNOWN → old SUBMITTED regression; fixed with a durable running observation marker and two recovery regressions before rerunning the complete gate. Result parsing and reports remain the M7/M8 scope. Python emits one upstream Starlette/AnyIO deprecation warning; all tests execute and pass.
- Decisions: lease ownership only reduces duplicate polling; Job/Execution identity, CAS, terminal monotonicity and idempotent reservation release enforce correctness. V6 adds the running marker without changing the already-applied V5 migration. Cloud installation/startup commands were re-executed successfully and their configuration draft was saved; draft publication and fresh-task restoration have not been performed.
