# Public adapters and enterprise integration

This repository validates the control plane and backend engineering. Its synthetic files, contours and engineering values do not reproduce commercial Solver algorithms or establish production KPIs.

| Public implementation | Enterprise equivalent | Replacement boundary |
| --- | --- | --- |
| Independent MockSolver process | OptiStruct / NVH / Crash / CFD Solver | `worker/adapters/solver.py` SolverAdapter plus fixed profile mapping; implement allowlisted argv, verified inputs, native exit/result interpretation and process identity |
| HTTP ComputeAgentClient / FastAPI Agent | Internal server Agent or controlled SSH wrapper | `backend/.../agent/ComputeAgentClient.java`, `worker/agents`; preserve durable request fingerprint, query, cancel, heartbeat and callbacks |
| Matplotlib synthetic post processor | HyperView Python/Tcl automation | `worker/post/processor.py`, HyperViewAdapter protocol; preserve frozen render specification, condition/Subcase, manifest and atomic version publication |
| python-pptx draft report | Enterprise template library | `worker/post/report.py`; preserve exact canonical metrics/comparisons, embedded artifacts and manual approval semantics |
| Docker shared-data volume | NFS / GPFS / NAS | shared `/data` contract; validate permissions, atomic rename/fsync, path safety, latency and storage monitoring |
| MySQL mock License admission | Enterprise License Server query | `backend/.../resource/ResourceReservationService.java` and License observation integration; keep transactional admission and conservative uncertain-capacity handling |
| Three public mock nodes | CAE CPU compute pool | Node registry/heartbeats and controlled profiles; discover real capacity/capabilities without making Redis authoritative |
| Direct worker launch | Slurm / PBS / enterprise scheduler | BatchSchedulerAdapter submit/query/cancel protocol; map native scheduler identity/status without changing Job/Execution identity |
| STRUCTURE canonical parser / PILOT parsers | Native result formats and approved engineering definitions | ResultParser SPI, controlled solver profile and unit/condition/Subcase validation; retain immutable parser versions |

OptiStructAdapter, HyperViewAdapter and BatchSchedulerAdapter are explicit integration protocols. Commercial implementations, credentials and licenses are not supplied. The default four public SolverAdapter implementations are wired to actual independent mock processes and are exercised by the acceptance suite.

Commercial adoption also needs enterprise identity, secret management, TLS, network isolation, retention/backups, license vendor semantics and engineering validation. These do not block the reproducible public Compose deployment.
