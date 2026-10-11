# Result parsing and version provenance

Java owns the canonical result model. `ResultParser` exposes `supports(analysisType, solverType)`, `parse(context)` and `version()`. The public implementation accepts only the four explicitly configured synthetic solver types. Replacing the Solver or implementing a commercial result adapter does not require changing scheduling, reservation, messaging or report arithmetic.

## Supported results

| Analysis | Canonical metrics | Coverage |
|---|---|---|
| STRUCTURE | `MASS`, `TORSIONAL_STIFFNESS`, `BENDING_STIFFNESS`, `MODE_1_FREQUENCY` through `MODE_4_FREQUENCY` | Complete public reference path |
| NVH | `NVH_PEAK_FREQUENCY`, `NVH_PEAK_RESPONSE` | Pilot |
| CRASH | `CRASH_INTRUSION_MAX`, `CRASH_PEAK_ACCELERATION`, `CRASH_ENERGY_BALANCE_RATIO` | Pilot |
| CFD | `CFD_CD`, `CFD_CL`, `CFD_RESIDUAL_FINAL`, `CFD_CONVERGED` | Pilot |

Canonical values come from actual `raw_result.json` output. Every metric retains its unit, dimension, condition and Subcase. Numeric values must be finite and fit the database precision. `CFD_CONVERGED` retains a boolean as text; it is never silently converted to a numeric KPI. Nonconvergence has `REVIEW_REQUIRED` status.

Both `v1` and `v2` retain the original canonical values. Version 2 adds consistency checks for increasing structural mode frequencies, crash energy balance, and CFD convergence versus residual. Its JSON schema also records the snapshot checksum, Solver profile and quality policy. Parser versions are real independent records and files, rather than a mutable label attached to one result.

## Input and execution identity

The parser derives all paths from the configured `DATA_ROOT`, database Job number and execution request ID:

```text
jobs/{jobNo}/input-checksums.json
jobs/{jobNo}/input/manifest.json
jobs/{jobNo}/executions/{executionRequestId}/execution-config.json
jobs/{jobNo}/executions/{executionRequestId}/state.json
jobs/{jobNo}/executions/{executionRequestId}/raw_result.json
jobs/{jobNo}/executions/{executionRequestId}/solver.log
```

The two stored workspace values must exactly match those derived paths. Snapshot verification checks every registered input file and its aggregate checksum. The manifest analysis, condition and positive integer Subcase must agree with the raw result. Configured resource and mock options retain the immutable request values. Configuration and Solver state must agree with the database request ID, remote execution ID and node; the immutable request itself must agree with the owning Job and execution. Solver state must confirm `SUCCESS` with exit code zero. Confirmed PID and process identity are checked when present. The synthetic raw parameters and seed must match the immutable request and snapshot. When a seed was omitted, Worker may encode its default as null; adding an arbitrary configured seed is rejected.

The public JSON format is bounded to 2 MiB per document. It rejects duplicate keys, trailing JSON, missing metrics, invalid booleans and unsupported schemas. Path traversal, absolute artifact URIs, symbolic links and nonregular files are rejected. SHA-256 is calculated from actual bytes. A second full verification occurs before the short commit transaction. Large downloadable files use bounded streaming buffers.

## Durable parsing and downstream commands

`RESULT_PARSE_REQUESTED` arrives on the real durable `cae.job.parse` RabbitMQ queue. The consumer uses manual acknowledgements and the durable Inbox name `result-parse-v1`. The original reconciliation command selects `v1`; explicit reparse commands carry their requested version. Execution request identity is checked before handling. A stale execution is acknowledged without changing the current Job or starting another Solver.

Filesystem reads, canonical parsing and derived-file generation happen before the short database transaction. Commit locks Job then execution, rechecks the current confirmed execution, and persists the parse record, metrics, registered artifacts, event and downstream Outbox command together. `UNIQUE(job_id, parser_version)` and `UNIQUE(job_id, parser_version, metric_code)` enforce business deduplication even when separate logical messages race. A completed version can replay only for its original execution and raw checksum. Its records are never overwritten.

`POST_PROCESS_REQUESTED` contains:

```json
{
  "jobNo": "CAE-20261011-000001",
  "executionId": 1,
  "executionRequestId": "exec-example",
  "analysisType": "STRUCTURE",
  "conditionCode": "BIW_TORSION",
  "subcase": 1,
  "rawChecksum": "<sha256>",
  "parseRecordId": 1,
  "parserVersion": "v1",
  "postVersion": 1
}
```

The subsequent stage must use this exact parse version and engineering condition. The queue remains durable until its real post processor is installed; an empty acknowledgement consumer is not used. The normal Job remains `POST_PROCESSING`. Reparse of an already successful Job preserves its completed Job state while creating the independent derived version.

A pending cancellation with an already confirmed successful execution is reconciled through the common durable terminal state applier before parsing. The commit repeats this check under its existing Job then execution locks. This repair uses stored evidence and the same transaction; it makes no network call, starts no process and preserves the sticky cancellation intent. It avoids exhausting delivery retries while waiting for a scheduled reconciliation scan. Confirmed cancelled Jobs and other obsolete stages acknowledge a stale parse command without creating downstream work.

Business failures record parse `FAILED`; a current Job in `POST_PROCESSING` becomes `POST_PROCESS_FAILED`. Existing successful parse versions and successful Jobs remain unchanged. Inbox retry and delayed Outbox publication use the existing bounded delivery policy. Exhausted messages are rejected into the real parse dead-letter queue. Database outages retain the unacknowledged delivery and use bounded recovery backoff.

## Results, files and authorization

```text
GET  /api/v1/jobs/{jobNo}/metrics?parserVersion=v1
GET  /api/v1/jobs/{jobNo}/parses
POST /api/v1/jobs/{jobNo}/reparse   {"parserVersion":"v2"}
GET  /api/v1/jobs/{jobNo}/artifacts
GET  /api/v1/artifacts/{artifactId}/download
GET  /api/v1/jobs/{jobNo}/artifacts/{artifactId}/download
```

Project access protects reads and downloads. Reparse additionally requires the Job owner or Admin and records an audit entry. Concurrent requests for an existing requested or completed version reuse the same parse record and do not generate repeated commands. The reparse API returns HTTP 202 and never creates an execution or contacts Solver submission.

The derived files are `jobs/{jobNo}/derived/{parserVersion}/result.json` and `result.csv`. JSON includes execution provenance, canonical metrics and coverage maturity. CSV retains parser version, metric code/name, numeric/text value, unit, dimension, status, condition and Subcase. Complete-file publication uses atomic hard-link creation and verifies any existing file rather than replacing it. RAW_RESULT and SOLVER_LOG retain neutral source metadata so both parser versions refer to the same source bytes.

Artifact registration checks the execution and optional parse record against their owning Job. Relative URIs must remain inside that exact Job workspace. Downloads repeat the authorization and Job-path checks, then stream the source into a private temporary snapshot while verifying its checksum and size. The response streams only that verified snapshot; later in-place edits of the original file cannot change the downloaded bytes. Closing the response deletes its temporary file. Metadata or file tampering returns a conflict rather than serving unverified contents.

Micrometer exposes actual parse duration and delivery counters: `cae.result.parse.duration`, `cae.mq.parse.acked`, `cae.mq.parse.retry` and `cae.mq.parse.dead`.
