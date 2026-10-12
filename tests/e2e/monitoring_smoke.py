#!/usr/bin/env python3
"""Verify real exported families, Prometheus queries and provisioned Grafana."""

import base64
import datetime
import json
from pathlib import Path
import urllib.parse
import urllib.request

from messaging_smoke import LocalServices
from reconciliation_smoke import until

ROOT = Path(__file__).resolve().parents[2]
services = LocalServices()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def read(url, headers=None, as_json=True):
    with opener.open(
        urllib.request.Request(url, headers=headers or {}), timeout=15
    ) as response:
        text = response.read().decode()
    return json.loads(text) if as_json else text


scrape = read("http://127.0.0.1:8088/actuator/prometheus", as_json=False)
families = [
    "cae_jobs_total",
    "cae_jobs_running",
    "cae_jobs_waiting_resource",
    "cae_job_submit_latency_seconds",
    "cae_job_queue_seconds",
    "cae_outbox_pending",
    "cae_mq_consume_failure_total",
    "cae_agent_offline_total",
    "cae_resource_reservation_total",
    "cae_reports_generated_total",
]
for name in families:
    assert "# TYPE " + name + " " in scrape, name
assert sum(line.startswith("cae_jobs_total{") for line in scrape.splitlines()) == 76
targets = until(
    lambda: read("http://127.0.0.1:9090/api/v1/targets"),
    lambda result: any(
        target["labels"]["job"] == "cae-backend" and target["health"] == "up"
        for target in result["data"]["activeTargets"]
    ),
    "Prometheus backend target up",
    45,
)
dashboard = json.loads(
    (ROOT / "deploy/grafana/dashboards/cae-platform.json").read_text()
)
queries = []
for panel in dashboard["panels"]:
    for target in panel.get("targets", []):
        expression = target["expr"]
        result = read(
            "http://127.0.0.1:9090/api/v1/query?"
            + urllib.parse.urlencode({"query": expression})
        )
        assert result["status"] == "success" and result["data"]["result"], expression
        queries.append(
            {"expression": expression, "series": len(result["data"]["result"])}
        )
credentials = services.container(
    "grafana",
    'printf "%s\\n%s" "$GF_SECURITY_ADMIN_USER" "$GF_SECURITY_ADMIN_PASSWORD"',
)
username, password = credentials.split("\n", 1)
header = {
    "Authorization": "Basic "
    + base64.b64encode(f"{username}:{password}".encode()).decode()
}
grafana = read("http://127.0.0.1:3000/api/dashboards/uid/cae-platform", header)
assert grafana["dashboard"]["timezone"] == "Asia/Shanghai"
assert len(grafana["dashboard"]["panels"]) == len(dashboard["panels"])
evidence = {
    "atUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "requiredMetricFamilies": families,
    "fixedJobSeries": 76,
    "backendTarget": next(
        target
        for target in targets["data"]["activeTargets"]
        if target["labels"]["job"] == "cae-backend"
    ),
    "dashboardQueries": queries,
    "grafana": {
        "uid": grafana["dashboard"]["uid"],
        "timezone": grafana["dashboard"]["timezone"],
        "panels": len(grafana["dashboard"]["panels"]),
    },
}
print(json.dumps(evidence, ensure_ascii=False, indent=2))
