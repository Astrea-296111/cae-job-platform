Implemented in M8. The final source/API and acceptance evidence are documented in `../post-and-reports.md` and `../validation/m8-validation.md`; the preparation details below are retained as design rationale. Actual source identity additionally binds PID/processIdentity; V8 uses unified `cae_pipeline_stage`, plus separate `cae_report` and `cae_report_generation_request`.

# M8 Post + Report implementation contract

Status: design review prepared against the actual M7 implementation. This document does not assert that M8 is implemented. Engineering requirements come from the full reproduction specification, sections 24–28 and Milestone 8.

## Existing M7 boundary

`POST_PROCESS_REQUESTED` already contains `jobNo`, `executionId`, `executionRequestId`, `analysisType`, `conditionCode`, `subcase`, `rawChecksum`, `parseRecordId`, `parserVersion`, and `postVersion: 1`. There are no client paths. The Rabbit route is `job.postprocess`; the subsequent existing route is `REPORT_REQUESTED` → `job.report`.

The authoritative source is one `cae_result_parse_record` in `COMPLETE`, identified by its primary key. Read metrics with `WHERE parse_record_id=?`; never select a Job's latest metric rows while consuming an older version's command. Verify that all rows also carry the expected Job, execution, parser version, condition, and subcase. A render does not allocate CPU/license resources or submit a Solver.

M7 derived files are `jobs/{jobNo}/derived/{parserVersion}/result.json` and `result.csv`. The result JSON contains identity, parser version, maturity, and canonical metrics; it does not contain the database parse record id. Bind its registered artifact SHA and its contents to the selected parse record and metrics before sending a request.

`ArtifactRepository.register(...)` is transactional and checks Job/execution/parse/version ownership. Use `ArtifactFileStore.describe(...)` outside the transaction to compute the actual returned file size/SHA. Registration must not trust Worker metadata alone. The authenticated artifact download routes already exist.

## Worker requests

Add service-token protected `POST /worker/v1/post/process` and `POST /worker/v1/reports/generate`. Keep the Solver RPC's short timeout unchanged; rendering uses a separate client with a 30-second request timeout, a fixed configured Worker origin, and no redirects. Start with the successful execution's original node; an unavailable render node is a retryable stage failure and never causes Solver resubmission. Do not add an implicit alternate-node or free-form endpoint policy.

Use a strict Pydantic contract with aliases in camelCase and unknown fields forbidden. Id/version/condition fields are validated before any filesystem operation. A post request is:

```json
{
  "schemaVersion": 1,
  "jobNo": "CAE-20261011-000123",
  "executionId": 123,
  "executionRequestId": "exec-request-123",
  "externalExecutionId": "remote-execution-123",
  "nodeId": "node-a",
  "parseRecordId": 123,
  "parserVersion": "v1",
  "postVersion": 1,
  "analysisType": "STRUCTURE",
  "conditionCode": "BIW_TORSION",
  "subcase": 1,
  "rawChecksum": "<64 lowercase hex>",
  "snapshotChecksum": "<64 lowercase hex>",
  "resultJsonChecksum": "<64 lowercase hex>",
  "context": {
    "projectCode": "BIW-DEMO",
    "projectName": "Synthetic BIW Platform",
    "modelCode": "BIW",
    "modelName": "Body In White Synthetic",
    "modelRevision": "V37",
    "maturity": "COMPLETE"
  },
  "metrics": [
    {
      "metricCode": "MASS",
      "metricName": "Mass",
      "valueNum": "245.1000000000",
      "valueText": null,
      "unit": "kg",
      "dimension": "MASS",
      "status": "VALID",
      "conditionCode": "BIW_TORSION",
      "subcase": 1
    }
  ],
  "comparisons": [],
  "specs": [
    {
      "conditionCode": "BIW_TORSION",
      "resultType": "CONTOUR",
      "component": "SYNTHETIC_BODY",
      "subcase": 1,
      "frame": 0,
      "view": "ISOMETRIC",
      "selectionSet": "PUBLIC_DEMO",
      "outputName": "contour.png"
    }
  ]
}
```

Numeric metric/comparison values use finite decimal strings, with at most the existing database precision/scale, to preserve Java `BigDecimal` values. The Worker compares them using `Decimal`; conversion to floating point is limited to plotting coordinates. Exactly one of `valueNum`/`valueText` is non-null. Metric codes are unique and the requested set equals the selected canonical result's set. Sort metrics/comparisons by metric code and specs by output name before persisting the payload. The example is abbreviated; full structure includes all seven M7 metrics and four post specs.

Allowed public render result types are `CONTOUR`, `MODE_SHAPE`, `COMPARISON`, and `CURVE`, producing four actual PNG files. `component`, `view`, `selectionSet`, `frame`, and safe output basename are explicit whitelist choices, not commands for a commercial tool. All specs must have the request's exact condition and subcase. A valid modal example is `BIW_MODAL`, subcase `2`; it must not silently use torsion/subcase `1`.

The report request extends the same frozen source/context/metrics/comparisons envelope with `reportVersion`, `templateCode`, `templateVersion`, `generatedAt`, `postManifestChecksum`, and the selected `postVersion`; it omits `specs`. For the public structure path use `templateCode: STRUCTURE_DRAFT`, `templateVersion: v1`. `generatedAt` is established once when the report intent is persisted, rather than recomputed on retry. The Worker reads only the corresponding verified post manifest and its declared images; the client cannot supply image paths. NVH/Crash/CFD remain explicitly marked `PILOT`; they may use a simplified template while structure/torsion/modal receive the complete four-slide report.

## Fixed paths and immutable publication

The Worker constructs all paths from its configured `DATA_ROOT`:

```text
jobs/{jobNo}/input/manifest.json
jobs/{jobNo}/executions/{executionRequestId}/execution-config.json
jobs/{jobNo}/executions/{executionRequestId}/state.json
jobs/{jobNo}/executions/{executionRequestId}/raw_result.json
jobs/{jobNo}/derived/{parserVersion}/result.json
jobs/{jobNo}/derived/{parserVersion}/post-v{postVersion}/
  contour.png
  mode-shape.png
  comparison.png
  curve.png
  post-manifest.json
jobs/{jobNo}/reports/{parserVersion}/report-v{reportVersion}/
  report.pptx
  report-manifest.json
```

Reject symlinks throughout the path, non-regular files, absolute output names, traversal, backslashes, colons, NUL, and unexpected file sets. Validate snapshot SHA, successful execution state and matching immutable request/external/node identities, raw checksum, manifest analysis/condition/subcase, result JSON checksum and metric equality. The output filename/relative URI are generated by the Worker, then checked again by Java against the exact selected version directory.

Stage identity is `(parseRecordId, postVersion)` or `(parseRecordId, reportVersion)`. Freeze the full request JSON in SQL at first request. A Worker computes `requestFingerprint = SHA-256(canonical request JSON)` using recursively sorted ASCII keys, compact separators, UTF-8 unescaped text, and decimal strings. Java must implement the same recipe and verify a shared golden fixture. Do not include a changing retry attempt, trace id, lease token, or current timestamp in the fingerprint.

Acquire a filesystem lock for the fixed stage identity. Render into a private temporary directory on the same filesystem, finish and validate every file, write the manifest last, fsync, and atomically publish the completed version directory without replacing an existing version. Incomplete private directories are never returned or registered. On a matching retry, verify manifest fingerprint and every artifact's SHA/size, then return the existing descriptor without changing bytes or mtime. Same version with a different fingerprint returns `409`; corrupted immutable output returns an explicit stage failure rather than silently overwriting it. A new source/spec/baseline/template requires a new stage version.

Use an `asyncio.to_thread` boundary with a small semaphore and a Matplotlib lock. Select `Agg` before importing pyplot. Rendering must not block the FastAPI event loop, heartbeat, or execution callbacks. Four figure types must contain visible synthetic/public-demo labels; they are not real HyperView results.

## Worker completion response

Return `status: COMPLETE`, all request source/version identities, `requestFingerprint`, and descriptors. Reports also return `reportStatus: DRAFT`; the Worker cannot return `REVIEWED` or `APPROVED`.

```json
{
  "schemaVersion": 1,
  "status": "COMPLETE",
  "jobNo": "CAE-20261011-000123",
  "executionId": 123,
  "executionRequestId": "exec-request-123",
  "parseRecordId": 123,
  "parserVersion": "v1",
  "postVersion": 1,
  "conditionCode": "BIW_TORSION",
  "subcase": 1,
  "rawChecksum": "<64 lowercase hex>",
  "requestFingerprint": "<64 lowercase hex>",
  "artifacts": [
    {
      "artifactType": "IMAGE",
      "resultType": "CONTOUR",
      "fileName": "contour.png",
      "relativePath": "jobs/CAE-20261011-000123/derived/v1/post-v1/contour.png",
      "sizeBytes": 12345,
      "checksum": "<64 lowercase hex>",
      "contentType": "image/png",
      "metadata": {
        "conditionCode": "BIW_TORSION",
        "subcase": 1,
        "component": "SYNTHETIC_BODY",
        "frame": 0,
        "view": "ISOMETRIC",
        "selectionSet": "PUBLIC_DEMO"
      }
    }
  ]
}
```

The comparison/curve descriptor may use `CURVE`; the report descriptor uses `REPORT_PPTX`, MIME `application/vnd.openxmlformats-officedocument.presentationml.presentation`. The response also declares its manifest descriptor so Java can verify the source/version chain. Every descriptor must match an allowed filename/type and exact stage path, including Job ownership. The report manifest records KPI identities, post artifact checksums, comparisons, template/version, slide count, and `DRAFT`. Java verifies actual PNG signature/dimensions and a valid PPTX zip/slide count before completing a stage.

## Baseline comparison in Java

The initial automatic baseline is the latest earlier successful Job in the same project and model, with the same analysis, condition, subcase, and parser version; its revision may differ. Exclude the current Job. Require the baseline parse record to be `COMPLETE` and the chosen Job to be `SUCCESS`. A Project Lead/Admin may explicitly choose a compatible baseline; project membership is checked on both Jobs. Freeze `baselineJobNo` and `baselineParseRecordId` with the stage request. A later baseline choice creates a new post/report version, preserving earlier artifacts and approval history.

Java computes per matching metric using `BigDecimal`: `delta=current-baseline`; `deltaPercent=delta/baseline*100`, with explicit precision/rounding. Require matching metric code, unit, and dimension; numeric/text mismatch is not silently coerced. Store/send `baseline`, `current`, `delta`, `deltaPercent`, `unit`, and a reason/status. Missing baseline/value is `N/A`; zero baseline gives `deltaPercent: null` and `ZERO_BASELINE`. The Worker displays these values and does not recompute them. A text/bool metric may compare equality and show `N/A` for numeric deltas.

## SQL stage records and main Job binding

Add V8 stage records with `UNIQUE(parse_record_id, stage_version)` for post and report. Each record holds Job/execution/parse/version identity, immutable request JSON/fingerprint, `REQUESTED|RUNNING|COMPLETE|FAILED`, error/retry details, claim owner/generation/expiry, and completion/requested-next timestamps. Report rows separately hold review state `DRAFT|REVIEWED|APPROVED`, artifact id, reviewer/approver and times. A generated report is `DRAFT` even after the Job reaches `SUCCESS`.

Add `cae_job.active_pipeline_parse_id`, `active_post_version`, `active_report_version`, and `pipeline_generation`. These fields identify the pipeline that is allowed to advance the main Job; stage claim generation identifies one owner's right to commit a particular render attempt. They must not be confused.

Binding rules, always under the Job lock:

1. On the first `COMPLETE` parse for the current successful execution, M7's commit binds that parse id and post/report version `1`, and increments pipeline generation before writing the post command. Existing M6/M7 backlogs are backfilled deterministically with the earliest `COMPLETE` parse id for the current execution; do not select the first Rabbit delivery that happens to arrive.
2. A new parse completed while the main Job is `POST_PROCESS_FAILED` or `REPORT_FAILED` may explicitly recover it: replace the active parse binding, reset active post/report versions to `1`, increment pipeline generation, clear the stage failure, and transition to `POST_PROCESSING`. Add the explicit `REPORT_FAILED → POST_PROCESSING` recovery edge. The parse source must still be the current confirmed successful execution. An already complete parse selected for recovery uses a dedicated activation command; returning an existing parse alone does not imply recovery.
3. While `POST_PROCESSING` or `REPORTING`, another parser version is an independent historical pipeline unless an explicit authorized activation changes the binding. This avoids accidental takeover by an older/duplicate delivery.
4. Once the Job is `SUCCESS`, a reparse or new report version stays an independent historical pipeline. Its own report may fail or complete, but the Job keeps `SUCCESS`; its original result/report artifacts and review history remain accessible.
5. A stage commit may update the main Job only when current execution, active parse id, active stage version, and captured pipeline generation all match. A stale version may complete its own history and request its own next stage once; it cannot advance, clear failure, or fail the main Job. `CANCEL_REQUESTED` waits for reconciliation; `CANCELLED` or replaced execution is a stale no-op for main Job transitions.

Post success registers its four PNGs/manifest, completes the post record, and creates exactly one `REPORT_REQUESTED` in the same transaction. A main post success advances `POST_PROCESSING → REPORTING`. Report success registers PPTX/manifest and completes the report row as `DRAFT`; a main report success advances `REPORTING → SUCCESS`. The main Job can therefore be successful while its draft still requires engineering review.

Do not hold database locks across HTTP, filesystem reads, PNG/PPTX generation, or file checksum verification. The sequence is a short transaction to lock `Job → Execution → stage`, claim an expiring fenced lease, and read/freeze intent; RPC and descriptor verification outside a transaction; then the same lock order and lease/identity/source/generation checks in a short commit. Expired owners cannot register artifacts or transition stages. A backend crash after Worker publication is recovered by replaying the same frozen request.

## Retry and bounded failures

Implement the existing `POST /jobs/{jobNo}/retry` for stage failures without creating `EXECUTION_SUBMIT_REQUESTED` or a new execution. It retains its existing idempotency key semantics and records `JOB_RETRY` audit.

* `POST_PROCESS_FAILED` caused by parsing: request the failed parser version again against the existing successful execution. There is no successful post source yet. A failed parse cannot be rendered.
* `POST_PROCESS_FAILED` with a complete active parse: transition to `POST_PROCESSING`, reset only its failed post stage to `REQUESTED`, clear stage failure, increment pipeline generation, and issue a new logical post event using the identical frozen payload/version. Preserve already completed parse/metrics and Solver identity.
* `REPORT_FAILED`: transition to `REPORTING`, reset only its failed report stage to `REQUESTED`, increment pipeline generation, and request the same frozen report. Preserve successful post images and parse/metrics. Do not rerender successful images.
* Request edits such as a different baseline, post spec, or report template allocate a new positive stage version and a new fingerprint. They do not overwrite failed or reviewed immutable output versions.

Business failures are bounded using the current Inbox business-attempt policy and dead-lettering after exhaustion. Worker HTTP timeout/lost response may mean the file was already published; retrying the same stage is safe. A known permanent input/spec/source/identity mismatch becomes `POST_PROCESS_FAILED` or `REPORT_GENERATION_FAILED` with stage-safe details, bounded retries, and eventual DLQ. Connection/database outages and lock failures use capped infrastructure backoff/NACK without incorrectly marking a Job failed; storage integrity errors are business failures, not infinite infrastructure retries. ACK only after the stage transaction and its next Outbox intent are durable.

If a completed immutable artifact is corrupted, preserve the error and require a new stage version for reconstruction; do not claim that same-version retry can repair it. Every retry path must prove that execution count, execution request id, PID/process identity, and resource ledger are unchanged.

## Manual review and approval

Expose project-scoped report history/detail and generation endpoints, plus explicit review/approve actions. A project member can generate a draft; selecting/changing a baseline and marking `REVIEWED` require Project Lead/Admin membership, and `APPROVED` requires Admin. Repeated identical actions are idempotent. Use `DRAFT → REVIEWED → APPROVED`; do not automatically approve or demote a reviewed version when generating another draft. Record `REPORT_GENERATE`, `REPORT_REVIEW`, and `REPORT_APPROVE` audit with actor, target report id, before/after state, and time. Service-token Worker endpoints cannot invoke review/approval.

## Acceptance evidence to implement

The complete structure/modal report contains at least four slides: project/model/revision/condition/subcase; exact canonical KPI table with units and parser version; baseline/current/delta/percentage table and chart; embedded generated result images and a visible conclusion draft placeholder. Every slide visibly says `DRAFT / Engineering review required`; every image says synthetic public demo. No numerical engineering conclusion is inferred by an LLM.

Tests must open real PNGs with Pillow and real PPTX with python-pptx, check dimensions/slide count/embedded image SHA, and read slide text/table values. Verify report KPI condition/subcase equals every selected post image's condition/subcase, including modal subcase `2`; mismatched spec/request/result identities must fail before output publication. Test missing and zero baselines, actual V36/V37 comparison arithmetic, version-specific metric selection, concurrent duplicate rendering, backend/Worker restart replay with unchanged SHA/mtime, changed fingerprint conflict, corrupt artifact failure, role restrictions, and old-version late success/failure after explicit main binding replacement.

End-to-end evidence must create real structure/torsion and modal Jobs, allow Solver → parse → post → report → `SUCCESS`, download and open all four images and the PPTX, verify database registrations and event chain, then reparse `v2`/generate another draft with one unchanged Solver execution. Consume pre-existing M6/M7 parse/post backlog safely without purging queues or deleting user data. Inject a post/report failure and demonstrate stage-only retry plus eventual resource totals at the existing baseline.
