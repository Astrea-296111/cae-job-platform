# Milestone 8 validation

The final gate passed 332 Java tests and 60 Python tests; Spotless, Ruff, ESLint, Vue TypeScript and Vite passed. Backend/Worker Docker images built, V8 applied, and all 11 Compose services started healthy. Thirteen new Java cases use real MySQL/RabbitMQ and HTTP to a real Matplotlib/python-pptx Worker, or exact comparison/fingerprint arithmetic. Fourteen Worker cases open real PNG/PPTX files, verify KPI/image condition and Subcase, concurrent immutable publication, restart replay, bad source/spec rejection, token enforcement and health responsiveness.

The [live evidence](m8-report-smoke.json) uses real independent Solver processes and the actual Compose queue pipeline:

| Job | Scenario | Result |
| --- | --- | --- |
| CAE-20261012-000092 | V36 torsion baseline | SUCCESS; four PNGs and five-slide DRAFT |
| CAE-20261012-000093 | V37 current | SUCCESS; all seven KPI values and exact backend delta/percentage checked |
| CAE-20261012-000094 | BIW_MODAL, Subcase 2 | SUCCESS; every KPI/image/slide uses the selected condition/Subcase |
| Job 93 | Six concurrent parser-v2 requests | One v2 parse/report, unchanged original Solver; approved v1 SHA retained |
| Job 93 | Explicit baseline/new report version | One idempotent v1/report-v2 DRAFT; original approval retained |
| CAE-20261012-000095 | Post failure | Four attempts → actual DLQ; explicit same-stage retry → SUCCESS |
| CAE-20261012-000096 | Report failure | Four attempts → actual DLQ; retry retains all image hashes/mtimes and Solver identity |
| CAE-20261012-000097 | Backend restart during rendering | Reclaimed stage lease; identical Worker containers, execution/PID and published post hashes/mtimes; SUCCESS |

All downloaded artifacts match registered size/SHA. Pillow verifies four distinct actual 1200×720 PNGs. python-pptx opens all five slides, checks every canonical KPI value/unit/condition/Subcase and verifies that the four embedded image SHA values equal the selected PNG files. Every slide visibly remains DRAFT. Java comparison values match independent Decimal subtraction and six-place HALF_UP percentage calculations. Report files are also retained under `.local/acceptance/{jobNo}/{parserVersion}/report-v{version}/` for inspection.

Engineer review and Lead approval are refused; Lead review and Admin approval are manual/idempotent. A subsequent draft keeps the older approval record and file. Reparse/new report generation never create another execution. A test deliberately lets v1 complete after activating v2, and then fails the old v1 report; neither changes the main successful v2 Job. Another test obtains the Job lock while real HTTP rendering is paused, proving that RPC does not hold it.

The image upgrade initially started new consumers before all old Worker endpoints had been replaced. Legacy Jobs 83/89/90 consequently exhausted bounded connection retries. After all Workers were healthy, authorized stage retry completed those structure/NVH/CRASH reports with the original execution records unchanged. This recovery is included in the evidence. Existing M6/M7 queues were consumed without purging; M5's historical submit DLQ message remains readable. Final node CPU/memory reserved/used counters and license counters return to zero.

The public renderer remains synthetic. Pilot reports are visibly PILOT; no commercial HyperView or engineering sign-off is claimed. Manual approval updates the review record and preserves the generated draft binary. Full browser workflows and SSE are M9 work.
