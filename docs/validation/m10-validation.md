# M10 验收

Java 346、Python 60、全部已有集成测试、Spotless/Ruff/ESLint/type/build、backend/frontend 镜像与 11 服务健康通过。完整门禁日志 `/tmp/cae-m10-final-gate.log`。真实 Compose 结果见 [JSON](m10-failure-smoke.json)。

| # | 场景 | 结果 |
| --- | --- | --- |
| 1 | structure-success | PASS |
| 2 | missing-include-no-solver | PASS |
| 3 | duplicate-submit-one-process | PASS |
| 4 | accepted-response-timeout-one-process | PASS |
| 5 | runtime-failure-with-log | PASS |
| 6 | resource-wait-auto-resume | PASS |
| 7 | offline-unknown-preserves-process-and-capacity | PASS |
| 8 | cancel-race-confirmed-release | PASS |
| 9 | duplicate-parse-unique-records | PASS |
| 10 | forty-child-partial-failure-batch | PASS |
| 11 | report-kpi-image-condition-subcase-match | PASS |
| 12 | backend-restart-solver-continues | PASS |

40 子任务批次 `BATCH-0da52b50-7903-408f-a0a5-09fe8e259288`：37 成功、两个运行故障和一个 INCLUDE 缺失失败，父级 PARTIAL_FAILED/100%，分配到 node-a, node-b, node-c；46 个实际采样显示接入暂停。所有 37 个成功子任务的真实 PNG/PPTX 与 canonical 指标、工况、Subcase 和嵌入图片 SHA 校验通过。

延迟回复场景可能由回调先确认原执行；因此脚本真实重投原持久 Rabbit 命令并检查 ACK，记录实际 Inbox retryCount（本次为 0），不把回调确认伪装成自动重试。执行/PID/原请求保持唯一。

首次压力验收发现的提交前负面巡检竞态已修复，新集成测试证明 404 不阻断尚未送达的原提交命令，已确认执行仍按 UNKNOWN 保留容量。首次批次的原身份恢复与新批次自动通过分别保存证据，见 [恢复时序](m10-race-recovery.json)。

所有脚本修改的 drain/heartbeat 恢复，历史 DLQ 证据保留，最终 CPU/内存/License 回到基线。成功报告本地下载位于 `.local/acceptance/CAE-*/v1/report-v1/report.pptx`。

Chromium 实际创建 4 子任务批次并看到 COMPLETED/100%，四个子项均具有真实节点/PID，页面无异常；证据见 [批次 UI](m10-batch-ui.json)。
