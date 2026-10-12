# Synthetic post processing and draft reports

The public pipeline uses actual Matplotlib PNGs and python-pptx files. `PostProcessor` and `ReportGenerator` are replaceable Python protocols; `MockHyperViewPostProcessor` is synthetic and does not invoke HyperView. STRUCTURE torsion/modal reports contain five slides: project/model/condition, canonical KPI table, historical comparison, contour/mode images, and a curve with an engineering conclusion draft. Every slide visibly retains `DRAFT / Engineering review required`.

```mermaid
sequenceDiagram
  participant MQ as RabbitMQ
  participant B as Backend
  participant DB as MySQL
  participant W as Worker
  MQ->>B: POST_PROCESS_REQUESTED (parse/version identity)
  B->>DB: Job → Execution → stage locks; fenced lease
  B->>W: Frozen post request (outside transaction)
  W->>W: Verify snapshot/state/raw/export; lock version; publish four PNGs
  W-->>B: Fingerprint, SHA, metadata and manifest
  B->>DB: Verify files; register artifacts; complete post; REPORT_REQUESTED Outbox
  B-->>MQ: ACK after durable Inbox completion
  MQ->>B: REPORT_REQUESTED
  B->>W: Frozen report context and exact post manifest
  W->>W: Embed verified image bytes; publish draft PPTX
  B->>DB: Register draft; main REPORTING → SUCCESS
```

Requests bind Job/execution/request/external/node/PID/process identity, parse record, parser version, raw/snapshot/export checksums, condition and Subcase. All canonical metric values are checked against the selected immutable result JSON. Worker paths are derived from configured DATA_ROOT and controlled IDs; callers cannot supply a workspace, template path or image path. Post specs include condition, result type, component, Subcase, frame, view, selection set and output basename. Four fixed outputs are `contour.png`, `mode-shape.png`, `comparison.png`, and `curve.png`.

Java computes comparison deltas and percentages with BigDecimal; percentages use six decimal places and HALF_UP rounding. Missing baselines display N/A, and a zero denominator produces a null percentage. The Worker displays supplied values. Automatic baseline selection requires an earlier successful Job in the same project/model/analysis/condition/Subcase/parser version; Lead/Admin may explicitly choose a compatible baseline.

Stages use `(parse_record_id, kind, stage_version)` uniqueness and a durable frozen request/fingerprint. The shared Unicode/null/decimal fingerprint fixture is `docs/contracts/render-fingerprint.json`. RPC, checksum reads and rendering occur outside database transactions. A 90-second stage lease uses owner and generation checks; the Inbox lease is 60 seconds. Waiting for another stage owner creates a durable delayed command without consuming business failure attempts. Business failures use bounded Inbox retry/DLQ; database outages retain unacked deliveries.

The Job's active parse/post/report versions and pipeline generation fence its main status. Historical versions can complete their own artifacts and reports without changing a successful Job. Old v1 success/failure cannot replace a newly activated v2 pipeline. POST/REPORT retry retains the original successful execution, parsed metrics and frozen request. Report retry retains completed image bytes and mtimes. New baseline/template context requires a new stage version.

Worker publication uses a filesystem lock, private temporary directory, fsynced files, manifest written last, and atomic directory rename. A matching replay verifies fingerprints/files and preserves SHA and mtime. Different inputs or corrupt completed output cannot overwrite an existing version. A bounded thread semaphore and Matplotlib lock keep the event loop, health and heartbeat responsive.

Authenticated API routes:

| Route | Behavior |
| --- | --- |
| `GET /api/v1/jobs/{jobNo}/pipeline` | Stage history, leases, frozen context and errors |
| `GET /api/v1/jobs/{jobNo}/reports` | Versioned report history |
| `POST /api/v1/jobs/{jobNo}/reports` | Generate another draft; `parserVersion`, optional `baselineJobNo`, reusable `idempotencyKey` |
| `GET /api/v1/jobs/{jobNo}/comparison` | Authoritative comparison values |
| `GET /api/v1/reports?projectId=...` | Project-authorized report list |
| `GET /api/v1/reports/{id}` | Report context, review state and download URL |
| `POST /api/v1/reports/{id}/review` | Project Lead/Admin, DRAFT → REVIEWED |
| `POST /api/v1/reports/{id}/approve` | Admin, REVIEWED → APPROVED |
| `POST /api/v1/jobs/{jobNo}/retry` | Owner/Admin stage retry; Solver options cannot change |

Each review/approval is audited and idempotent. Generation never approves a report. Manual approval updates the version's review record; it preserves the originally generated draft file. New versions remain DRAFT and retain older approval history. Report display timestamps use Asia/Shanghai; request and database timestamps remain UTC.

Download uses the existing project-authorized artifact endpoint. Example storage:

```text
jobs/{jobNo}/derived/v1/post-v1/{four PNGs,post-manifest.json}
jobs/{jobNo}/reports/v1/report-v1/{report.pptx,report-manifest.json}
```

Service-token Worker routes are `/worker/v1/post/process` and `/worker/v1/reports/generate`. `/worker/v1/faults/render` supports controlled post/report failures and delays, optionally scoped to one Job; it does not accept commands. Worker tokens cannot review or approve reports.

`PipelineIntegrationTest` uses real MySQL/RabbitMQ and HTTP to a real Python renderer, including stale versions, no database lock during RPC, DLQ and stage-only recovery. Worker tests open generated PNG/PPTX files and verify all embedded images and KPI condition/Subcase. Live Compose evidence is recorded separately in `docs/validation/m8-validation.md` after execution.
