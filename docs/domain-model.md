# Domain model

MySQL migration files V1–V10 are authoritative. These persisted aggregates form the implemented domain:

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

| Table | Purpose and invariant |
|---|---|
| `cae_job_execution`, `cae_job_submission_request` | Attempt history and durable user submit/retry intent |
| `cae_compute_node`, `cae_license_pool`, `cae_resource_reservation`, `cae_resource_profile` | Durable capacity, ledger and controlled profiles |
| `cae_outbox_event`, `cae_message_inbox` | Atomic command intent, leased publish/consume and deduplication |
| `cae_execution_poll_lease` | Owner-fenced reconciliation polling |
| `cae_result_parse_record`, `result_metric`, `cae_artifact` | Immutable parser versions, canonical metrics, artifact registry |
| `cae_pipeline_stage`, `cae_report`, `cae_report_generation_request` | Frozen post/report requests, leases, report/review versions and generation replay |
| `cae_job_create_request`, `cae_report_template` | Durable create replay and controlled report templates |
| `cae_batch`, `cae_batch_item`, `cae_batch_admission_gate` | Parent/intents/children and fenced bounded admission |

The shared-data filesystem stores immutable snapshots and registered artifacts; it is not the business state database. Worker SQLite preserves local execution/callback facts. See `progress.md` for actual implemented and tested coverage.

The expected unique keys for execution and derived data are `(job_id, attempt_no)`, `submit_request_id`, `external_execution_id`, `(job_id, parser_version, metric_code)` and `(job_id, parser_version)`. These are durability constraints, not cache entries.
