# 前端工作台与通知契约

Vue 3/TypeScript + Pinia + Vue Router + Axios + Element Plus + ECharts。页面全部接真实 `/api/v1`，Nginx 在 `http://localhost:8088` 提供工作台及 API/SSE 代理。

| 页面 | 可执行行为 |
| --- | --- |
| `/login` | 调用 `/auth/me` 验证账户；认证只驻留当前标签页内存 |
| `/` | 授权项目聚合、真实状态分布、当前节点负载、排队样本口径与最近任务 |
| `/jobs/new` | 选择项目、模型版本、学科、公开 Case/工况/Subcase、资源档位及优先级；仅创建或创建并提交 |
| `/jobs` | 六类服务端筛选、Shanghai 日期窗口、分页及 URL 条件保留 |
| `/jobs/:jobNo` | 状态/时间线、执行身份与历史、资源、受控日志、版本指标、认证图片、PPTX 与全部工件；提交/取消/阶段重试/v2 解析/新草稿 |
| `/compute-nodes` | 三节点 CPU/内存账本及 Agent 观测、心跳/tags/License；Admin drain 及有版本冲突检查的资源档位编辑 |
| `/reports` | 历史草稿与下载、后端基线候选及精确比较值、Lead Review/Admin Approve；Admin 控制模板启停 |

Case 选择绑定 manifest 的 revision/analysis/condition/Subcase。用户筛选模型版本或学科后，只显示兼容 Case。CPU、内存与 License 使用已持久化的档位快照。公开 Synthetic 数据和 PILOT 状态始终可见。

## 创建与动作幂等

`POST /jobs` 支持 `Idempotency-Key`。MySQL `cae_job_create_request` 以 `(actor,key)` 唯一约束及行锁串行化并发请求，规范化输入指纹不同返回 409。Job 与请求记录在同一事务提交。重放使用当前锁定读，避免 Repeatable Read 下等待锁之前的旧快照看不到刚提交 Job。

浏览器在超时/响应丢失时保留原输入及键；创建成功后，只重试原 Job 的 submit。修改创建输入才换键。submit/retry/cancel/生成草稿操作成功后再结束本次意图，下一次主动操作用新键。界面不把 202 当 SUCCESS，也不把取消请求当 CANCELLED。

## SSE

`GET /events/stream?jobNo=&projectId=` 接收 Basic header 和可选 `Last-Event-ID`；浏览器使用 `fetch` 流读取，处理跨块 UTF-8、CRLF 和多行事件。

服务端读取已提交 `cae_job_event`，按当前项目成员过滤，数据仅含通知身份、状态、版本、时间，不传详细事件中的敏感内容。全局最多 200、单用户 60 连接，4 个发送线程、256 项有界任务队列、单连接单个发送任务；每批最多 100 条事件，15 秒 heartbeat，30 分钟连接超时。数据库暂时不可用保留 cursor；慢/断开连接不持有 Job/资源事务锁。

收到 ready/job、重连成功后重新 GET 权威视图。重连退避最多 15 秒加抖动；详情另有 10 秒轮询。退出与卸载中止流；401 退出会话，403 停止流。Nginx 关闭响应缓冲。通知 payload 不直接改写前端 Job 状态。

## 查询、时间与日志

Job Redis 缓存 TTL 5 秒，每次读取先查询 MySQL 中 Job 身份/版本并检查项目权限。版本不符走 MySQL；事务提交后通过 Lua 单调版本 fence 删除缓存，旧查询不能覆盖新版本。Redis 失败仍能查 MySQL。

Job 列表和 Dashboard 日期支持 IANA `timeZone`，浏览器使用 `Asia/Shanghai`，本地日期起止转换为 UTC `[start,end)`；持久化及事件时间仍使用 UTC。完成率只包括成功/失败终态，取消与活跃任务不进入分母；无样本显示空值。平均排队时间来自当前执行 `started_at - queued_at` 的非负实际样本，同时返回样本/排除数。节点资源为当前观测。

`GET /jobs/{jobNo}/logs?executionId=&lines=200` 在读文件前检查成员和执行归属。路径只能由受控 Job/request 身份推导；拒绝 symlink，最多 500 行和末尾 256 KiB，移除 ANSI/控制字符、认证头、密码与 Service token。浏览器使用文本 `<pre>`，不解释日志中的 HTML。

图片和 PPTX 通过带认证的 Blob 请求下载；不把认证放 URL，Blob URL 卸载时释放。报告审核记录与不可变 PPTX 二进制分离。

## 验证

`scripts/check.sh backend frontend` 运行全部已有 Java/Python/集成检查、前端 lint/type/build 和应用镜像构建。`scripts/test-ui.sh` 使用固定 Playwright 1.51.0 与 Chromium，连接真实 Nginx，不替换业务 API。它故意丢弃一个已被后端接受的创建响应，随后由页面重试同一请求，并核对一个 Job/执行。

浏览器证据见 `docs/validation/m9-ui-smoke.json`；截图与下载的真实 PPTX 保存在 `.local/acceptance/m9/`。浏览器依赖为可选验收工具；开发与容器运行不依赖浏览器。
