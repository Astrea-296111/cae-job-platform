# M11 完整验收

公开复现 M0–M11 已完整实现，规范第 56 节的 [17 项交付](../final-delivery.md) 均有代码或实际证据。所有工程结果来自 Synthetic Case；商业软件无需安装。

最终流程覆盖 clean-clone mental check、固定依赖 bootstrap、应用 image build、Compose up/health、Flyway V1–V10 fresh schema/seed、实际 demo 和全测试。Docker build context 不依赖目标文件、主机虚拟环境或 node_modules；Maven/npm/Python 源依赖可由固定安装入口恢复。所有项目运行数据、卷和已有 DLQ 证据保留。

| 验收 | 实际结果/证据 |
| --- | --- |
| 完整 Java | 347 passed / 0 failure / 0 error / 0 skipped，含真实 MySQL/Redis/Rabbit/HTTP/文件集成 |
| Python | 60 passed；15 个上游 deprecation warnings，无跳过 |
| 静态/前端 | Spotless/Ruff/ESLint/vue-tsc/Vite/Compose config 通过；主 Element Plus chunk 体积 advisory 无失败 |
| Docker | backend/frontend/worker 最新源镜像 build 成功；11 服务健康 |
| 监控 | 必需十项 family、76 Job 状态/学科序列、backend target UP、13 个 Grafana 面板与全部 PromQL 返回实际序列：[JSON](m11-monitoring.json) |
| 六项 Benchmark | 查询 1000、并发创建 100、资源事务 50、批次子任务 100、publisher confirm 1000、SSE 50；实测错误率全部 0%；[数字与测量边界](../benchmark.md) |
| 公开 demo | Jobs 612/613/614、报告 223/224/225；每个七指标、四 PNG、五页 PPTX/全部图像嵌入 SHA 正确，V36/V37 比较准确，modal Subcase 2：[JSON](m11-demo.json) |
| 完整 Chromium | 新 Jobs 615/616；失去 accepted 创建响应重放、v2 不改 v1、显式基准/新 DRAFT 不跑 Solver、Lead Review/Admin Approve、License 使用列/API 一致、资源管理、390px、SSE 200、无页面异常/持久凭证：[JSON](m11-ui-smoke.json) |
| Batch Chromium | 新四子任务全部 SUCCESS/COMPLETED/100%，真实 node/PID，无页面异常：[JSON](m11-batch-ui.json) |
| 故障可靠性 | 十二实际场景通过，详见 [M10 完整说明](m10-validation.md)；最终 Java 测试继续覆盖相应回归 |
| 资源恢复 | 三节点 ONLINE/未 drain，CPU/内存 used/reserved 全零；四种 License 全零：[账本](m11-final-ledgers.json) |
| 云设置 | 最终 bootstrap/startup commands 实际通过；[配置草稿边界](../cloud-environment.md) |

完整发布复核日志在当前环境 `/tmp/cae-m11-release-java.log`、`cae-m11-release-python.log`、`cae-m11-release-frontend.log`；原始性能与领域证据保存在已提交 JSON 中。最终测试统计和镜像/服务快照见 [发布检查](m11-release-checks.json)。

本次监控实际验收发现新 Prometheus client 规范化 Gauge `_total` 导致 dashboard 缺数据，改用同版本 Micrometer 官方兼容实现并增加真实导出断言后完整重验。SSE 性能客户端曾仅关闭无界流 response 导致测试仍保留 transport；现显式关闭 socket，通过新建 Job 的实际事件验证 50 → 0，原始观察如实保存。

自动 Job SUCCESS 与报告 DRAFT/人工 Review/Approve 分离。历史 v1/v2、执行/PID 和输入/产物身份被保留。未宣称 exactly-once、真实商业工程精度或企业生产提升比例。
