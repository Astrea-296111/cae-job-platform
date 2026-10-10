# ADR-003: Direct Agent execution with durable process identity

Status: accepted for Milestone 3.

The node Agent keeps a SQLite registry in its persistent node directory. A unique executionRequestId identifies one attempt. Repeating the same identifier and immutable request returns the registered attempt; changing its request is a conflict. Each attempt uses an independent Python subprocess and an execution-owned output directory, rather than an in-process timer.

The durable claim commits before process launch. The child writes its PID and creation time into an atomic state file. PID alone is insufficient because operating systems reuse it. Queries and cancellation verify the process identity, and terminal output remains queryable after Agent restart.

SQLite commit and operating-system process creation cannot form one atomic transaction. If a crash leaves a durable claim whose process cannot be identified, the Agent reports UNKNOWN and does not launch another solver for that claim. The control plane must query and reconcile it; an HTTP timeout does not prove rejection. This favors avoiding duplicate expensive solver execution over automatically rerunning an ambiguous attempt.

Only a known solver profile selects the fixed Python command. The API does not accept executable names, command lines or shell fragments. The Agent verifies the Job's immutable input checksum before creating the process and confines paths to the shared Job directory.
