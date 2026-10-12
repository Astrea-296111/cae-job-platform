#!/usr/bin/env python3
"""Three real public jobs, exact comparisons and downloadable structural/modal PPTX."""

import json
from report_smoke import (
    user,
    create,
    submit,
    success,
    validate_report,
    verify_comparison,
)

api = user("engineer", "engineer123")
catalog = api.require("GET", "/api/v1/cases")
owned = []
evidence = []
for case in [
    "STRUCTURE-CASE-BASELINE",
    "STRUCTURE-CASE-CURRENT",
    "STRUCTURE-CASE-MODAL",
]:
    entry = next(row for row in catalog if row["caseId"] == case)
    job = create(api, entry, owned)
    submit(api, job)
    success(api, job)
    report, result = validate_report(api, job)
    result["downloadUrl"] = report["downloadUrl"]
    evidence.append(result)
verify_comparison(
    api.require("GET", "/api/v1/reports/" + str(evidence[1]["reportId"])),
    api.require("GET", "/api/v1/reports/" + str(evidence[0]["reportId"])),
)
print(
    json.dumps(
        {"scenario": "public baseline/current/modal full demo", "reports": evidence},
        ensure_ascii=False,
        indent=2,
    )
)
