# Domain model

MySQL migration files are authoritative. Milestone 1 introduces these persisted aggregates:

| Table | Purpose and invariant |
|---|---|
| `cae_project` | Unique project code and owning user |
| `cae_project_member` | Membership checked server-side before project/job access |
| `cae_model` | Model belongs to project; model code unique inside project |
| `cae_model_revision` | Revision belongs to model; revision unique inside model |
| `cae_job` | Unique readable JobNo, selected project/revision, analysis/profile/input/resource, status/version CAS |
| `cae_job_event` | Ordered business timeline, committed with state changes |
| `cae_audit_log` | Actor, operation, target and before/after JSON |

Model revision selection must resolve to the selected project. `submitter_id` comes from authentication, never request JSON. JobNo uses the database-generated key, not `SELECT MAX()+1`.

Later migrations introduce independent execution attempts, node/resource reservations, license admission, Outbox events, canonical metrics, parser versions, artifacts, draft reports, resource/report profiles and batch parents. See `progress.md` for actual implemented and tested milestone coverage.

The expected unique keys for execution and derived data are `(job_id, attempt_no)`, `submit_request_id`, `external_execution_id`, `(job_id, parser_version, metric_code)` and `(job_id, parser_version)`. These are durability constraints, not cache entries.
