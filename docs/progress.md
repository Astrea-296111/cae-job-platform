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
- [ ] Case validation, safe includes, immutable input snapshot and failures.
- Tests: not yet run.
- Known issues: none established.
