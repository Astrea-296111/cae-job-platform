# Metrics and provisioned dashboard

The backend exposes `/actuator/prometheus`. Compose Prometheus scrapes it every ten seconds. Grafana provisions datasource `cae-prometheus` and dashboard `cae-platform` with Asia/Shanghai display time. Default local Grafana login: `admin/demo-grafana-password` (override with `GRAFANA_ADMIN_PASSWORD`).

| Exported family | Meaning/type |
| --- | --- |
| `cae_jobs_total{status,analysis_type}` | Current MySQL Job counts; Gauge, fixed 19×4 labels |
| `cae_jobs_running`, `cae_jobs_waiting_resource` | Current business state counts; Gauge |
| `cae_job_submit_latency_seconds` | Actual Agent submit RPC plus acceptance persistence/error handling duration; histogram; idempotent no-op excluded |
| `cae_job_queue_seconds`, `cae_job_queue_samples` | Mean queued-to-start interval of current attempts and included sample count; nonnegative known timestamps only |
| `cae_outbox_pending`, `cae_outbox_oldest_pending_seconds` | Durable NEW/RETRY/PUBLISHING backlog and age |
| `cae_mq_consume_failure_total` | Process-lifetime observed malformed/business-failed command consumptions; Counter |
| `cae_agent_offline_total` | Current OFFLINE nodes; Gauge, despite the specification's `_total` spelling |
| `cae_resource_reservation_total`, `cae_reports_generated_total` | Durable database row counts including historical versions; Gauge |
| `cae_node_*`, `cae_license_tokens` | Fixed three-node CPU/memory ledger and four-feature reserved/in-use/total tokens |
| `cae_sse_connections`, `cae_batches_active` | Actual live SSE registry and active batch parents |
| `cae_metrics_last_success_timestamp_seconds`, `cae_metrics_refresh_error_total` | Snapshot freshness and refresh failure Counter |

Platform database aggregates refresh once per five seconds into an immutable in-memory snapshot. Scrapes do not perform SQL for each platform sample. Failed refreshes retain the last snapshot and expose its age. Identifiers such as JobNo, execution id and username never become metric labels. General JVM/HTTP/connection-pool metrics remain available through Actuator.

Spring Boot 3.3.6 uses Micrometer 1.13.8. We select its official `micrometer-registry-prometheus-simpleclient` compatibility implementation to preserve the required Gauge names ending in `_total`. The newer Prometheus client normalizes those suffixes away. Integration acceptance checks the actual scrape text, and live monitoring acceptance queries every provisioned dashboard expression.

Job count, offline count and database totals can decrease with state changes or scoped data removal, so they are documented as Gauges. Use direct snapshots for these; apply `rate()` only to Counters. The queue statistic covers retained historical attempts, not just the latest benchmark. Load figures are recorded separately in [local benchmark](benchmark.md).
