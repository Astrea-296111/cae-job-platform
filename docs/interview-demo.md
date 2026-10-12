# Reproducible interview demonstration

Use the public dataset and describe all measurements as `[LOCAL-BENCH]`. Do not present synthetic values or this deployment as company production evidence.

1. `make up`; open `http://localhost:8088`, sign in as engineer. Create V36 baseline, V37 current and BIW_MODAL Subcase 2 jobs. `make demo` runs the same real API path and verifies downloaded PNG/PPTX bytes, metrics, comparisons and embedded images.
2. Open a Job timeline: input validation freezes recursive INCLUDE files and SHA; scheduling persists one attempt and resource reservation; the Agent launches an independent PID. Solver success then advances through parsing, postprocessing and draft report. Download the five-slide PPTX.
3. Reparse v2 and generate a new report version. Show unchanged Solver identity, retained v1 bytes, explicit baseline selection and DRAFT status. Lead reviews and Admin approves; human approval is a separate audited action.
4. `make failure-demo` demonstrates all twelve scenarios, including duplicates, missing input, resource waiting, response uncertainty, runtime failure, UNKNOWN recovery, cancellation and backend restart. Show the original execution/PID and capacity ledger rather than promising exactly-once delivery.
5. Create a batch in the UI; explain bounded intent admission, Outbox backpressure and fair discipline scanning. `make benchmark` measures actual samples and capacity refusals. Open Prometheus `http://localhost:9090` and Grafana `http://localhost:3000` for the provisioned CAE dashboard.

The five strongest code walkthroughs are the CAS transition policy, resource transaction, Outbox/Inbox pair, terminal reconciliation and immutable report pipeline. Exact paths and recorded results are in [final delivery](final-delivery.md).
