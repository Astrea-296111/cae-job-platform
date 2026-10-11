# Milestone 7 validation

The final gate passed 319 Java tests (180 existing, 102 parser unit, 33 result integration, and four parse messaging cases) and 46 Python tests, including the four terminal-publication regressions. Spotless, Ruff formatting/lint for all 25 Worker Python files, ESLint, Vue TypeScript checks, and the Vite build passed. Both backend and Worker Docker images were built and deployed; the backend applied V7, and all 11 Compose services were healthy before the live smoke. The first live run passed without a runtime script repair.

Review found that the Mock Solver published terminal state before writing its final log footer. A parser observing SUCCESS could therefore register a checksum before the log finished changing. The terminal state now publishes after the footer is written, flushed, fsynced, and the log is closed. Four targeted tests execute the real Solver run for SUCCESS, runtime failure, cancellation, and timeout; their terminal-publication hook verifies complete durable/closed outputs and unchanged log SHA after run returns. These tests passed as part of the complete 46-test Worker gate, and the repaired image runs on all three nodes.

`ResultParserTest` verifies all four strategy implementations, typed CFD convergence, storage bounds, unsupported schemas/identity, finite-number requirements, missing outputs, units, and actual v2 physics-consistency rules. `ResultParsingIntegrationTest` uses real owned MySQL fixtures to verify concurrent version claims, transactionally unique metrics/commands, stale execution fencing, repair/reparse without another execution, integrity/path checks, and project/owner authorization. `ParseMessagingIntegrationTest` uses actual RabbitMQ for duplicate delivery, bounded database-business failures, and DLQ. Artifact tests include modified source inodes, cross-Job metadata, download integrity, and traversal rejection.

The committed [parser smoke evidence](m7-parser-smoke.json) comes from `tests/e2e/parser_smoke.py` against real backend, MySQL, RabbitMQ, and independent Worker Solvers. Startup verified all nine catalog cases, modal subcase 2, three fresh reachable ONLINE nodes, and zero reservation/license counters. The five owned Jobs completed actual Solvers and v1 parsing:

| Job | Analysis / condition | Subcase | Metrics | Maturity |
| --- | --- | --- | --- | --- |
| CAE-20261011-000087 | STRUCTURE / BIW_TORSION | 1 | 7 | COMPLETE |
| CAE-20261011-000088 | STRUCTURE / BIW_MODAL | 2 | 7 | COMPLETE |
| CAE-20261011-000089 | NVH / CABIN_NVH | 1 | 2 | PILOT |
| CAE-20261011-000090 | CRASH / FRONTAL_CRASH | 1 | 3 | PILOT |
| CAE-20261011-000091 | CFD / DRAG | 1 | 4 | PILOT |

Every canonical numeric/text value, engineering unit/dimension, condition, subcase, status, and parser maturity matched the downloaded real RAW_RESULT artifact. Every Solver log contained the correct Job and process start identity. Actual RESULT_JSON and RESULT_CSV downloads retained all those values and conditions, including every modal row carrying `BIW_MODAL`/subcase 2. Sizes and SHA-256 hashes matched durable metadata through both canonical and Job-scoped download endpoints.

Each completed parse produced exactly one SENT `POST_PROCESS_REQUESTED` command with its correct parse record, execution/request identity, raw checksum, analysis, parser/post versions, condition, and subcase. All corresponding persistent delivery-mode-2 messages remained in the actual post queue; that queue has no consumer until the M8 processor is installed.

For Job 87, two actual Rabbit parse messages exercised a reused logical ID with a new physical ID and another logical ID for the same Job/v1. Broker ACK counters increased by two, both logical Inbox records were DONE, and the original one parse record, seven metrics, two versioned artifacts, immutable metadata, and independent Solver execution were unchanged.

Six concurrent parser-v2 requests for Job 87 resolved to one v2 parse record (ID 7) and one post command. Its original v1 record (ID 6), JSON/CSV bytes and checksums remained unchanged. The v2 JSON/CSV used distinct `derived/v2/` paths while retaining the same raw checksum, execution 10, and process identity. V2 JSON also carried schema version 2 and snapshot/solver provenance with the `physics-consistency-v2` policy. Reparsing created no Solver attempt.

The M6 successful Jobs 82/83 were consumed into completed v1 parses and each relayed to a persistent post message. The historical M5 malformed-message DLQ payload remained readable with requeue before and after the smoke. All owned execution reservations were RELEASED, all three nodes' used/reserved CPU and memory counters returned to zero, and every license feature returned to its zero baseline. Queues, volumes, old artifacts, and earlier evidence were preserved.

M7 ends at canonical parsing and durable post dispatch. PNG and report completion belong to M8.
