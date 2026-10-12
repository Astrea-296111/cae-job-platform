Implemented in M8. The final source/API and acceptance evidence are documented in `../post-and-reports.md` and `../validation/m8-validation.md`; the preparation details below are retained as design rationale. Actual source identity additionally binds PID/processIdentity; V8 uses unified `cae_pipeline_stage`, plus separate `cae_report` and `cae_report_generation_request`.

# M8 Worker implementation preparation

Status: design only, pending the root agent's M7 acceptance and stable commit. No Worker implementation or dependency change is represented by this document. The governing requirements are the reproduction specification, sections 24–26, and the actual wire agreement in [m8-contract.md](m8-contract.md).

## Owned files and boundaries

The Worker implementation owner will add a `worker/post/` package with strict Pydantic contracts, verified source loading, immutable stage storage, a `PostProcessor` protocol and `MockHyperViewPostProcessor`, a `ReportGenerator` protocol and `PptxDraftReportGenerator`, a pipeline service, and token-protected API routes. It will update `worker/app/main.py`, `worker/requirements.txt`, and `worker/Dockerfile`. Worker tests can be delegated to a separate test owner after the implementation interfaces are frozen. Java stage services, migrations, consumers, comparisons, API permissions, and main Job transitions belong to the backend owners.

The package must be copied by Docker, and its pinned runtime dependencies are Matplotlib 3.9.4, NumPy 2.2.2, python-pptx 1.0.2, Pillow 11.1.0, and lxml 5.3.0. Import Matplotlib with `Agg` before pyplot; do not run a render on import or startup.

## Request and source checks

Reject unknown JSON properties. Positive numeric IDs and stage versions, controlled analysis/parser/template codes, Job/request identity, lowercase SHA-256, bounded context strings, and safe fixed output filenames are validated before accessing the filesystem. Canonical metric decimal strings retain the selected Java values, use `Decimal` for equality, and must fit the metric storage precision. Comparison decimals require a separate width bound because backend subtraction or percentage may need more integer digits than an individual metric. Worker conversion to float is limited to drawing coordinates and never produces comparison deltas or percentages.

The configured node must match the selected successful execution's original node. Construct paths from `DATA_ROOT`, Job number, request id, parser version, and stage version. Do not accept a workspace, result path, output directory, template path, or image path from a request. Verify every path component and regular file; refuse symlinks, traversal, absolute output names, backslashes, colons, NUL, and unexpected stage files. Bound JSON source reads, refuse duplicate keys and nonfinite JSON numbers, and read each immutable source into a verified in-memory snapshot for the current invocation.

Reuse the existing immutable input verification using a reconstructed `SubmitRequest` from the actual execution configuration. Independently check config Job/request/external/node/profile/analysis/condition/snapshot identity, expected execution workspace and input directory, terminal state `SUCCESS` with exit code 0 and a valid process identity, manifest analysis/condition/subcase, raw SHA and raw source metadata, result JSON SHA and Job/execution/request/parser/raw/analysis/condition/subcase identity. The complete canonical metric set and every metric's value, unit, dimension, status, condition, and subcase must equal the selected result JSON. For v2, verify the recorded provenance snapshot checksum too. The database parse record id is supplied by Java and remains a mandatory stage identity; it is not invented from a file.

All four post specs use the same request condition and subcase. Use fixed result type/filename pairs: `CONTOUR/contour.png`, `MODE_SHAPE/mode-shape.png`, `COMPARISON/comparison.png`, and `CURVE/curve.png`. Whitelist component `SYNTHETIC_BODY`, frame 0, view `ISOMETRIC`, and selection set `PUBLIC_DEMO`. A modal request uses `BIW_MODAL`, subcase 2, throughout. A mismatched spec fails before publication.

## Publication and replay

Canonical fingerprints recursively sort JSON keys, use compact separators, retain UTF-8 text without ASCII escaping, preserve decimal strings, and exclude no frozen request fields. A shared Java/Python golden fixture covers Unicode, null values, lists, integer IDs, and decimal strings.

The fixed output directories are `jobs/{jobNo}/derived/{parserVersion}/post-v{postVersion}` and `jobs/{jobNo}/reports/{parserVersion}/report-v{reportVersion}`. Use an advisory file lock for one stage identity. Inside the lock, an existing completed version is verified by fingerprint, manifest identity, exact file set, SHA, size, PNG/PPTX content, and expected metadata; return it without touching file bytes or modification times. A changed request returns `IDEMPOTENCY_CONFLICT` with HTTP 409. A corrupt immutable version returns an explicit integrity error and cannot be overwritten by same-version retry.

New output is built in a private directory on the same filesystem. Finish and validate every artifact, fsync the files, write and fsync the manifest last, then publish the entire completed directory atomically without replacing any existing version. Only the published directory can be returned to Java. Failure cleanup affects only the current private staging directory. Preserve user directories and completed outputs.

The post manifest includes the full frozen request, request fingerprint, source/version identities, and descriptors for the four images. The report request selects the exact verified post manifest by checksum and fingerprint. Its source, context, metrics, and comparisons must equal that post's frozen envelope. Read and verify controlled image bytes once and feed those bytes to python-pptx; do not verify a pathname and then reopen potentially changed bytes. The report manifest records all KPI values and identities, selected image hashes, comparisons, template/version, slide count, and `DRAFT`.

## Actual PNG and PPTX contents

Draw a contour-like surface, a mode-shape-like visualization, a comparison bar chart using Java-supplied values, and a discipline-specific synthetic curve. Each figure has visible analysis/condition/subcase and `SYNTHETIC PUBLIC DEMO` labels. Inputs influence real plotted data; PNGs are not blank placeholders or copied static fixtures. Missing baseline is visibly `N/A`, and a supplied zero-baseline percentage remains `N/A`; the Worker does not calculate either.

The complete structure/torsion/modal draft has at least four slides. A practical five-slide arrangement is project/model/revision/condition/subcase/source information; all seven canonical KPI values with units and parser version; backend baseline/current/delta/percentage values and the comparison image; contour and mode-shape images; curve image with a visible engineering conclusion draft placeholder. Every slide visibly says `DRAFT / Engineering review required`. The four PNGs are embedded in the PPTX and preserve their selected condition/subcase. Pilot disciplines remain visibly `PILOT`, with no claim of professional solver results. No Worker action can review or approve a report.

## Concurrency and verification

API routes use the existing constant-time service token check. A small per-application semaphore bounds `asyncio.to_thread` rendering; a process-local Matplotlib lock protects pyplot. File locking handles duplicate requests across threads/processes. Execution submission, callbacks, heartbeat, and health stay responsive during rendering. No render starts a Solver or changes the execution registry.

Meaningful tests will open PNGs with Pillow and PPTX with python-pptx, inspect actual slide text/tables and embedded image hashes, verify modal subcase 2, exercise missing/zero baseline display, wrong identity or source checksum, unsafe path and symlink refusal, concurrent duplicate requests, changed fingerprint, immutable-output corruption, failure cleanup, restart replay with unchanged SHA/mtime, unknown fields, token enforcement, and responsiveness while rendering. Root alone runs Maven and the full milestone gate.
