# ADR-007: Durable MySQL resource and mock license admission

Status: accepted for Milestone 4.

Use MySQL as the authoritative resource and public mock-license ledger. Admission, reservation creation, execution association and Job state changes share a Spring transaction. Redis stores expiring node observations for UI reads; losing that cache cannot grant capacity or erase an active reservation. This extends the explicit-SQL decision in ADR-006.

Before admission, filter nodes by ONLINE status, compatible analysis tag, reachable workspace, administrative drain and available CPU/memory. Score eligible nodes using configured priority, waiting time, free CPU/memory ratios and running-job penalty; resolve equal scores by node ID. The conditional SQL reservation repeats the safety checks, including heartbeat freshness, because a preceding read or score cannot prevent a concurrent allocation. Capacity is `total - max(control-plane used, Agent observed used) - reserved`; overlapping observations do not count the same running allocation twice.

Resource-ledger operations lock Job, license pool, node and reservation in that order. License and node counters update conditionally. Failed node admission restores the license counter in the same transaction. One active reservation per Job and one reservation per execution are enforced by unique keys. A repeated immutable reservation request returns the existing reservation. RESERVED → IN_USE moves counters once; RESERVED/IN_USE → RELEASED decrements the corresponding counters once. Duplicate acceptance or release cannot subtract capacity again. Offline observations never release reservations.

The public mock license pools begin with OPTISTRUCT=4, NVH=2, CRASH=2 and CFD=2 tokens. They are a reproducible admission model, not an enterprise license-server adapter. Resource profiles are versioned administrator settings. Job creation snapshots CPU, memory, walltime, license feature/token count and profile version, so later profile edits cannot alter an existing attempt.

A Job without capacity remains WAITING_RESOURCE and is reconsidered by the bounded scheduler scan. A known attempt retains its execution request ID and reservation across an ambiguous Agent response. Capacity can be released for a definite pre-acceptance rejection or confirmed terminal execution; a timeout does not prove that a solver never started. Persistent cancellation intent and later reconciliation use the same ledger.

MySQL serializes contending admissions, which is suitable for this three-node public deployment. Higher throughput or a real license service requires a separate adapter preserving the same atomicity and release rules. The real-MySQL concurrency test uses 50 simultaneous transactions against 16 CPU cores, 32 GiB and four license tokens, admitting exactly four 4-core/8-GiB Jobs. See [Milestone 4 validation](../validation/m4-validation.md) for the verified invariants and remaining deployment gate.
