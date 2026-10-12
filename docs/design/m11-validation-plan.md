# M11 收尾验收设计

M10 稳定提交后实施。

- 指标由实际 MySQL/运行组件提供，按 status/analysis_type 及固定 node/feature 分类；缓存刷新快照，避免每条 Prometheus sample 发数据库请求。包含必需的十项平台指标、SSE 连接、Outbox 最旧待发时间、节点账本、License 与阶段。数据刷新失败保留最后值并暴露刷新成功时间。
- Grafana provisioning 使用现有 Prometheus datasource，明确 Asia/Shanghai 时区；实际检查 scrape target UP 和指标样本。
- 本地实测：1000 查询 API、100 并发创建、50 并发真实资源事务、100 子 Job 批次提交、1000 真实 Outbox publisher confirm、50 真实 SSE 连接。记录样本数、延迟分位数、错误率、业务拒绝数量与实际 MySQL CPU/内存/连接观察。资源和 Outbox 的 Java driver 使用测试 harness 独立 schema/vhost，不能把整个 HTTP 创建时延冒充资源申请或发布时延。
- 增补 RabbitMQ 选型 ADR、企业 Adapter 替换边界、可复现演示脚本及规范第 56 节的 17 项交付文档。已实现、Synthetic、PILOT、企业占位接口分别按实际能力说明。
- 最终重新跑 bootstrap/完整门禁/Compose 健康/公开 Case 演示；确认迁移、lockfile、docker build context、共享目录与环境草稿可复用。保留现有用户数据和队列证据。
