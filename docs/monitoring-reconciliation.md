# 执行监控与状态恢复

Backend 默认每 5 秒发现需要恢复的执行，使用 MySQL 持久租约分配轮询工作。发现查询不锁业务行；初始化租约采用 upsert，随后领取事务只锁租约行并使用 `SKIP LOCKED`。HTTP 查询在事务外执行。应用结果按 Job → Execution → lease 的顺序锁定，核对 owner、generation 和有效期限。租约只是减少重复工作，最终正确性由执行身份、当前执行关联、CAS、单调状态和幂等资源释放保证。

`ExecutionMonitor` 不创建 Execution，不申请资源，也不调用 Agent submit。externalExecutionId 已知时按该 ID 查询；接受结果丢失时按原 submitRequestId 查询。连接失败、远端 404、未知进程身份和过期心跳都不能证明 Solver 已停止，因此进入 UNKNOWN 并保留 CPU、内存和 License。运行超过请求 walltime 时记录检查日志并等待远端确认；本地时间估计不会直接终结执行或释放容量。

Agent callback 提供快速路径，周期轮询补齐丢失通知。以下地址由 `X-Service-Token` 验证：

```http
POST /internal/v1/agents/{nodeId}/executions/callback
```

```json
{
  "executionRequestId": "durable-request-id",
  "externalExecutionId": "node-a-external-id",
  "state": "RUNNING",
  "progress": 40.0,
  "pid": 1234,
  "processIdentity": "1234:1234567890.123456",
  "exitCode": null,
  "rawSolverCode": null,
  "rawMessage": null,
  "startedAt": "2026-10-11T00:00:00Z",
  "finishedAt": null
}
```

前三个字段必填。可选事实必须满足数值、长度和 UTC 时间约束。Backend 核对配置节点、request ID、external ID，以及已确认的 processIdentity 和 PID；远端 workspace 不会写入数据库。重复或过期事实返回 HTTP 200 的接受回执，`applied=false` 表示无需重投。已确认终态不能被 RUNNING、UNKNOWN 或另一终态覆盖，进度不会倒退。

首次确认 RUNNING 时，同一事务写入独立的 `running_observed_at`。该字段记录 Backend 已确认运行的事实，允许远端未提供可选的 startedAt；离线 UNKNOWN 不会清除它。心跳恢复后，即使收到旧 SUBMITTED 回调或轮询响应，执行仍恢复为 RUNNING，进度和执行身份保持不变。SUBMITTED 期存在 PID 只说明进程已创建，不会单独写入运行观察标记。V6 增量迁移为已有 RUNNING，及具有已确认 startedAt 的 UNKNOWN 执行补齐标记；已部署 V5 的校验和保持不变。

节点 OFFLINE 或心跳超过 20 秒时，RUNNING callback 仍保持 UNKNOWN；新心跳恢复 ONLINE 后按同一执行查询恢复。确认 SUCCESS、RUNTIME_FAILED、TIMEOUT、CANCELLED 的事实允许在节点离线期间终结执行并释放 Reservation。用户取消意图独立保存在执行的 cancelRequested；UNKNOWN 不会清除它，恢复后继续远端取消。取消 RPC 失败或响应身份不符时保留 CANCEL_FAILED、取消意图和资源；Solver 完成抢先胜出时仍走正常成功流程。失败响应不会改写后续重试已经创建的另一次执行。

轮询、callback、取消确认及历史回填共同调用 `RemoteExecutionStateService`。确认成功的事务完成执行终态、资源释放、Job POST_PROCESSING、SOLVER_SUCCESS 事件，以及 RESULT_PARSE_REQUESTED 事件和 Outbox。执行级 result_parse_requested_at 标记确保重复通知只生成一次解析命令。M6 保留真实 `cae.job.parse` 队列消息，M7 安装实际解析处理器后消费。

发现范围还包含历史 POST_PROCESSING + SUCCESS 且缺少解析标记的执行，以及确认终结却尚有 RESERVED/IN_USE 的 Reservation。历史回填使用已有持久终态证据，无需再次调用 Solver。旧 attempt 的观察不会改变 Job 当前执行；已证实终结的旧 Reservation 可以安全释放。

Backend 重启不影响独立 Worker Solver。租约到期后其他 Backend 领取原执行，查询同一 request/external ID，按同一事务路径恢复状态。Worker 重启后的终态通知可重复发送，回调身份检查及解析标记保持幂等。

测试使用实际 MySQL 事务和实际 HTTP 测试 Agent，按各测试拥有的执行 ID 操作租约和资源。覆盖并发与乱序回调、离线回调保持 UNKNOWN、远端 404 保留容量、原 request ID 恢复、取消与完成竞态、租约竞争和失效 owner、历史解析回填、终态资源修复。实际部署验收另行重启 Backend 并暂停 Worker 心跳，检查 PID、request ID、执行数量和资源账本。
