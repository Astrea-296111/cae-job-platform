# M9 验收

`2026-10-12` 完成：两次完整 `scripts/check.sh backend frontend` 中 Java 338、Python 60、全部现有真实基础设施集成测试、lint/type/build 通过。最终镜像构建通过并部署，11 个服务 healthy。日志位于 `/tmp/cae-m9-final-gate.log`，云环境重建后可重新运行相同命令。

真实 Chromium/Nginx 验收入口为 `scripts/test-ui.sh`，固定 Playwright 版本，不替换业务 API。结果见 [JSON](m9-ui-smoke.json)。Job 104 和 105 分别覆盖结构扭转与模态 Subcase 2，实际求解、解析、四图及五页 PPTX 下载；v1 下载 SHA 在 UI v2 重解析之后保持不变。独立记录的执行数仍为 1。

创建响应丢失通过先把请求发送到真实后端并取得 202，再断开该响应来注入。页面重试原键，回到原 Job，最终只有一次执行。Lead 的基线候选由后端兼容性查询提供，比较表与后端值一致，新报告为 v1/report-v2 DRAFT；人工审核和批准由不同角色完成。Admin 的 drain 后立即恢复，所有节点回到正常调度。

真实截图与下载在 `.local/acceptance/m9/`，其中 `dashboard.png`、`mobile-dashboard.png` 和 Job 104/105 的 metrics PNG 为本次成功运行。没有持久化密码；没有浏览器 page error；SSE 返回 200。日志限字节/限行、清洗、成员/执行归属和 symlink 拒绝，以及实际断线连接清理由 Java 集成验证。

前期浏览器脚本遇到两个断言定位问题，均已修正后重跑通过：v2 报告按时间排在 v1 前面，必须显式选择 v1 下载；点击生成按钮并不等于异步报告已完成，必须等待目标报告版本出现。并发创建测试发现的 Repeatable Read 当前读问题已修复，16 请求验证唯一 Job。
