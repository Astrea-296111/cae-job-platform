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

## Milestone 7
- [x] Strategy-based STRUCTURE result parsing with seven canonical metrics and NVH/CRASH/CFD pilot parsers; engineering units, condition/subcase, maturity and numeric storage bounds are enforced.
- [x] Durable parse Inbox/manual ACK/DLQ, versioned metric and parse history, audited concurrent v2 reparsing, immutable JSON/CSV artifacts and project-authorized verified snapshot downloads.
- [x] Nine public Cases including BIW_MODAL subcase 2; successful parsing atomically dispatches one persistent post command per parser version without another Solver attempt.
- Tests: Java 319 passed, including 102 Parser unit cases, 33 real-MySQL/file/API integration cases and four real-Rabbit parsing tests; Python 46 passed, including four terminal publication regressions. All format/lint/frontend build checks pass; backend and Worker Docker images rebuilt and deployed healthy. Five actual Solver cases (torsion, modal, NVH, CRASH, CFD) match every canonical metric and exported JSON/CSV value and SHA. Two actual duplicate deliveries are ACKed with Inbox DONE and no duplicate metrics/artifacts; six concurrent v2 requests preserve v1 bytes and the original execution/PID. M6 successful commands are parsed and relayed to unique persistent post messages, M5 DLQ evidence is preserved, and node/license counters return to their baseline. Evidence: `docs/validation/m7-parser-smoke.json` and `docs/validation/m7-validation.md`.
- Known issues: none blocking M7. PNG and draft report generation remain M8 scope. Pilot parsers are explicitly marked PILOT; synthetic files are not commercial Solver output. One upstream Starlette/AnyIO deprecation warning remains with all Python tests passing.
- Decisions: parsing binds immutable snapshot/request/state/raw identities; metric uniqueness is `(job_id, parser_version, metric_code)`. Storage constraint errors use bounded business retries; infrastructure outages retain deliveries. Downloads stream from a private verified snapshot. Confirmed SUCCESS during a cancellation race reuses the unified terminal applier without RPC. Solver terminal state is published only after the final log footer has been fsynced and the log closed.

## Milestone 8
- [x] Real synthetic Matplotlib contour/mode/comparison/curve PNGs, exact backend BigDecimal baseline comparisons, and five-slide python-pptx draft reports with all four images embedded.
- [x] Strict source/spec identity and condition/Subcase checks, immutable version publication/replay, durable post/report Inbox consumers, fenced stage leases and active main pipeline binding.
- [x] Versioned draft generation with durable request idempotency, project permissions, manual Lead review/Admin approval/audit, stage-only retry and historical v2 isolation.
- Tests: Java 332 passed (319 existing + ten real MySQL/Rabbit/HTTP-to-Python pipeline cases + three arithmetic/fingerprint cases); Python 60 passed including 14 actual PNG/PPTX/source/replay/API tests. Spotless/Ruff/ESLint/type/build pass, backend and Worker Docker images built and all 11 services deployed healthy. Actual V36 baseline/V37 current comparison, modal BIW_MODAL Subcase 2, six concurrent v2 requests, another report version, post/report four-attempt DLQ and same-stage retry, plus backend-only restart passed. Restart retained Worker containers, execution/PID, post hashes/mtimes and reclaimed the old stage lease. Evidence: `docs/validation/m8-report-smoke.json`.
- Known issues: no blocking M8 issues. A rolling image update briefly exposed the new consumers before three old Workers were replaced; three legacy stages exhausted connection retries. Explicit stage-only recovery completed all three with unchanged execution records; data and DLQ evidence were preserved. Python reports upstream Matplotlib/pyparsing and Starlette/AnyIO deprecation warnings with every test passing. Complete UI/SSE belong to M9.
- Decisions: UTC remains durable timestamp format; report display uses Asia/Shanghai. Worker publication and request fingerprints preserve immutable versions. Job SUCCESS and report DRAFT are distinct; only a human action changes review state. Manual approval preserves the original draft binary. V8 uses one stage table for both stages, and separate report/review and generation-idempotency tables.


## Milestone 9
- [x] Seven real Vue pages: authentication, dashboard, Case/model/discipline/profile creation, all six list filters, Job execution/log/metric/image/report details, node/license/profile administration, report baseline/manual review/template administration.
- [x] Bounded authorized SSE over committed MySQL events, header authentication, cursor reconnect, authoritative GET refresh, logout cleanup and polling fallback; permission-first version-fenced Redis cache and Shanghai date windows.
- [x] Durable create idempotency, bounded/redacted Job/Execution log tail with symlink refusal, controlled template state and server-compatible baseline candidates.
- Tests: Java 338 passed (332 existing + six real MySQL/Redis/concurrent MockMvc and actual HTTP SSE cases); Python 60 passed. Spotless/Ruff/ESLint/type/build pass; backend/frontend Docker images built, all 11 services healthy. Real Chromium via Nginx ran STRUCTURE current Job 104/execution 27 and BIW_MODAL Subcase 2 Job 105/execution 28. Each had seven metrics, four decoded 1200×720 PNGs and a downloaded five-slide/four-image PPTX. Lost accepted create response reused one Job/execution; UI v2 retained original execution/v1 bytes; Lead selected an explicit compatible baseline and generated another DRAFT with no Solver; Lead Review/Admin Approve, Admin drain/restore/profile dialog, 390px layout, SSE 200 and no page errors/persistent credentials passed. Evidence: `docs/validation/m9-ui-smoke.json`.
- Known issues: no blocking M9 issues. Vite reports a bundle size advisory for the main Element Plus chunk; ECharts uses selected modules. Existing upstream Python deprecations remain warnings. One transient npm download 403 recovered after refreshing cloud build configuration and retrying with TLS verification enabled.
- Decisions: MySQL create replay uses a current locking read after the idempotency claim, because Repeatable Read snapshots created before waiting cannot see a just-committed Job. SSE transport closure is handled without attempting JSON on a closed stream; notifications never replace Job truth. UI actions keep their key on failure and renew it after successful intent completion.


## Milestone 10
- [x] Persistent batch parents/intents/children, durable creation idempotency, atomic bounded admission with global fencing, and permission-filtered counts/progress/children.
- [x] Active-work/Outbox backpressure, real publish-confirm resume, per-discipline token caps and ranked fair scheduler scanning with priority aging; real Vue batch creation/detail/progress.
- [x] Twelve actual Compose scenarios passed, including 40 child Jobs with 37 successful full reports and three expected failures, three-node assignment and 46 paused admission samples. Missing INCLUDE launches no Solver; duplicate submit/parse preserve unique records; response delay plus original Rabbit replay preserves one process; runtime/cancel/UNKNOWN/restart maintain correct identities and ledgers.
- Tests: Java 346 passed (338 existing + seven MySQL/Rabbit/concurrent admission tests + one pre-submit monitoring regression), Python 60; format/lint/type/build and backend/frontend Docker build pass. All 11 services healthy; real Chromium created four children and observed COMPLETED/100% with real node/PID and no page errors. Evidence: `docs/validation/m10-failure-smoke.json`, `m10-batch-ui.json`, `m10-validation.md`.
- Known issues: no blocking M10 issues. Initial batch pressure found a pre-delivery negative-poll race. It was repaired and revalidated; the original unsent request was recovered without another attempt, and the new 40-child run completed automatically. Existing upstream warnings remain non-blocking.
- Decisions: negative observations of an unconfirmed SUBMITTING execution defer to its original durable submission consumer; confirmed/uncertain executions continue to retain capacity. Batch states are derived separately from the fixed 19 Job states. Admission uses bounded immutable intents, not an immediate MQ flood; failed creation items do not roll back siblings.
