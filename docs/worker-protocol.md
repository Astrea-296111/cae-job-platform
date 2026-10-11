# Compute Agent protocol

The public worker runs synthetic Python solvers. It never executes uploaded code or an arbitrary
command, and it does not require commercial CAE software. All `/worker/v1/**` endpoints require
`X-Service-Token`; the default `demo-service-token` is for the local demo only. `/health` returns
`{"status":"OK","nodeId":"node-a"}` without authentication.

## Submit and query

`POST /worker/v1/jobs/submit` returns HTTP 202 promptly after durable acceptance, before solver
completion. It accepts the following JSON:

```json
{
  "executionRequestId": "request-001",
  "jobNo": "CAE-20261010-000001",
  "workspace": "/data/jobs/CAE-20261010-000001",
  "solverProfile": "STRUCTURE_STANDARD",
  "analysisType": "STRUCTURE",
  "conditionCode": "BIW_TORSION",
  "snapshotChecksum": "64-character lowercase SHA-256",
  "resource": {"cpu": 8, "memoryGb": 16, "walltimeMin": 30},
  "mock": {
    "runtimeSeconds": 3,
    "successProbability": 1,
    "failureMode": "NONE",
    "progressInterval": 0.2,
    "responseDelayOnceSeconds": 0,
    "seed": 42
  }
}
```

The profile whitelist is `STRUCTURE_STANDARD`, `NVH_STANDARD`, `CRASH_STANDARD`, and `CFD_STANDARD`.
Profile, analysis type, and the configured node tags must agree. Unknown request fields are rejected.
Mock runtime is bounded to 0.05–600 seconds; response delay is bounded to 0–30 seconds and affects
only the original accepted request. Fault modes are `NONE`, `RUNTIME_FAILED`, and `TIMEOUT`.

Acceptance returns `externalExecutionId`, `accepted: true`, and the current `state`. Repeated input
with the same `executionRequestId` returns the original execution, including after terminal success.
The same ID with different input returns HTTP 409 `IDEMPOTENCY_CONFLICT`.

`GET /worker/v1/jobs/{externalExecutionId}` and
`GET /worker/v1/jobs/by-request/{executionRequestId}` return:

```json
{
  "externalExecutionId": "node-a-uuid",
  "executionRequestId": "request-001",
  "state": "RUNNING",
  "workspace": "/data/jobs/CAE-20261010-000001/executions/request-001",
  "pid": 123,
  "processIdentity": "123:1790000000.000000",
  "exitCode": null,
  "rawSolverCode": null,
  "rawMessage": null,
  "progress": 42.0,
  "startedAt": "2026-10-10T00:00:00+00:00",
  "finishedAt": null,
  "cancelRequested": false
}
```

Runtime states are `SUBMITTED`, `RUNNING`, `SUCCESS`, `RUNTIME_FAILED`, `TIMEOUT`, `CANCELLED`, and
`UNKNOWN`. Runtime error strings remain raw solver facts; the Java control plane maps platform
failure codes separately. UTC timestamps denote runtime facts.

## Shared filesystem and immutable snapshot

The submitted workspace must exactly match `DATA_ROOT/jobs/{jobNo}`. Absolute paths elsewhere,
traversal, symlinks, mismatched analysis/condition, unregistered input files, and checksum changes are
rejected before launching a process. `workspace/input-checksums.json` contains `files` and `checksum`.
The worker hashes each `workspace/input/{relative-file}` and verifies the exact registered file set.
The aggregate is SHA-256 of sorted UTF-8 lines `relative-name:sha256\n`, matching the Java snapshot
service. Solver input remains under `input/`; output is under `executions/{executionRequestId}/`.

Each attempt produces `execution-config.json`, `solver.log`, `progress.jsonl`, and atomically replaced
`state.json`. Success additionally produces `raw_result.json`. The raw result contains `schemaVersion`,
`analysisType`, `conditionCode`, `subcase`, `solverProfile`, `seed`, `parameters`, and the discipline's
canonical synthetic measurements at the top level. Structure provides mass, torsional and bending
stiffness, and four mode frequencies. Seed and case parameters make repeated results deterministic.

## Durability and cancellation

`agents/process_registry.py` claims a UNIQUE request ID using SQLite `BEGIN IMMEDIATE` and commits
the execution record before `Popen`. The request fingerprint is immutable. Only the successful
claimant launches the known `python -m mock_solver --config ...` command, without a shell, in a new
process session. SQLite also atomically checks active declared CPU/memory against node capacity.
Duplicate requests return the existing execution even when the node is full.

A restarted API reconciles the child process's atomic state files and PID plus process creation
time. A backend restart never stops the solver. A crash between durable claim and process launch is
deliberately conservative: the claim becomes `UNKNOWN` if no runtime evidence appears; the agent
never launches a second process for that request ID. This can require operator reconciliation and
avoids claiming exactly-once execution across the SQLite/process boundary.

`POST /worker/v1/jobs/{externalExecutionId}/cancel` persists a cancellation marker first, including
the child initialization window. The agent sends SIGTERM to the process group only after matching
PID/creation time and evidence that the child's signal handler is installed. The actual state stays
`SUBMITTED`/`RUNNING` with `cancelRequested: true` until the child confirms `CANCELLED`. Repeated
cancel is safe; a completed `SUCCESS` remains successful.

## Runtime callbacks

An independent asynchronous task POSTs state and progress changes to
`BACKEND_URL/internal/v1/agents/{nodeId}/executions/callback` using `X-Service-Token`. Its JSON
contains only `executionRequestId`, `externalExecutionId`, `state`, `progress`, `pid`,
`processIdentity`, `exitCode`, `rawSolverCode`, `rawMessage`, `startedAt`, and `finishedAt`.
It never supplies a filesystem path; the backend resolves the immutable attempt workspace itself.
Identity fields and the runtime state are mandatory; unavailable runtime facts are `null`.

The backend returns HTTP 200 with `accepted: true` after accepting runtime facts, including an
already applied duplicate (`applied: false`). The worker then durably records the acknowledged
payload fingerprint. Transport failures, rejected responses, and malformed acknowledgements retain
the notification and retry with bounded exponential delays of 0.5–30 seconds. A changed runtime
payload bypasses the delay for an older payload, so confirmed completion can be delivered promptly.
Each Agent startup replays durable terminal execution records once, including records previously
acknowledged before the restart. Replay never calls submit or launches a solver.

Callback delivery reads SQLite in bounded pages of 32 executions and permits at most four concurrent
HTTP requests. Separate active and terminal scan cursors scan current executions on every callback
iteration while older terminal records replay. Acknowledgements and retry times live in SQLite,
rather than an unbounded in-memory execution cache. HTTP requests have a three-second timeout,
ignore ambient proxies, and refuse
redirects. Lifespan shutdown cancels both callback and heartbeat tasks without stopping independent
solver processes. Callback failure cannot stop heartbeat emission or Agent API access.

Heartbeat fault injection affects only heartbeats; callbacks continue to carry runtime facts. A
nonterminal callback from a node whose heartbeat is offline does not clear the backend's `UNKNOWN`
status or release reservations. A fresh heartbeat followed by reconciliation restores a known live
state; a confirmed terminal callback can resolve the uncertainty directly. Backend polling uses the
same durable state application rules and remains available if callback delivery is interrupted.

## Node status and heartbeat

`GET /worker/v1/node/status` returns `nodeId`, `hostname`, `online`, `cpuTotal`, `cpuUsed`,
`cpuReserved`, `memoryTotalGb`, `memoryUsedGb`, `memoryReservedGb`, `solverTags`, `runningJobs`,
`workspaceReachable`, and
`lastHeartbeat`. `runningJobs` is a list of external execution IDs. Used resources represent the
declared synthetic execution slots; agent reserved counters are zero because the control plane owns
reservations. Unknown executions conservatively retain slots until reconciliation.

`workspaceReachable` checks that the configured shared filesystem root is a real directory with
read/write/traverse access. If `jobs/` already exists, it must also be a real accessible directory.
Before the first job, an absent `jobs/` is acceptable when the root is accessible. This signal lets
the scheduler reject a node whose shared storage is missing or inaccessible.

Every five seconds the agent POSTs this JSON to
`BACKEND_URL/internal/v1/agents/{nodeId}/heartbeat` with the service token. A bounded network failure
does not stop the agent or its solver. `POST /worker/v1/faults/heartbeat` with `{"enabled":false}`
temporarily suppresses heartbeats for the offline demonstration; `true` restores them. This endpoint
is authenticated and affects only heartbeat emission.

Configuration: `DATA_ROOT`, `REGISTRY_PATH`, `NODE_ID`, `CPU_TOTAL`, `MEMORY_TOTAL_GB`, `SOLVER_TAGS`
(comma separated), `BACKEND_URL`, `SERVICE_TOKEN`, `HEARTBEAT_INTERVAL_SECONDS`, and
`CALLBACK_INTERVAL_SECONDS` (default 0.5). Production token
values must be injected securely and never appear in saved execution configuration.
