# ADR-005: Shared filesystem for immutable inputs and solver data

Status: accepted.

Compose mounts one named shared-data volume at `/data` in the backend and all three Agents. MySQL stores Job state, checksum and artifact references. The filesystem stores the actual input snapshot and execution output. RabbitMQ messages carry identifiers rather than decks or result files.

The backend copies a validated Case into `/data/jobs/{jobNo}/input`, writes the SHA-256 file map and aggregate checksum, and commits the reference when validation reaches READY. Each Agent checks that checksum and uses a separate `/data/jobs/{jobNo}/executions/{executionRequestId}` output directory. Retries therefore preserve earlier attempt output.

The public deployment uses a local Docker volume. A production adapter would require shared storage such as NFS, GPFS or NAS, with compatible path mapping, permissions and atomic rename behavior on every node. A successful Agent heartbeat alone does not prove shared-storage reachability; node admission must also verify the workspace.
