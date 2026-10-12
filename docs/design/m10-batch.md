# M10 批次与背压实现

状态：已实现并通过完整门禁、12 个真实 Compose 场景及 Chromium 批次页面验收。

## 持久化和授权

`POST /api/v1/batches` 接受 name/projectId/idempotencyKey 和 1–200 个标准 Job 创建请求。所有子项属于同一授权项目；嵌套 Bean Validation、总 JSON 2 MiB 上限。`cae_batch` 的 `(submitter_id,idempotency_key)` 唯一与规范化请求指纹保障重放，改变输入返回 409。父级及全部意图行在一笔事务提交，意图行用 25 项 JDBC 批量插入；它们尚未触发 Solver。

`cae_batch_item` 唯一 `(batch_id,item_index)` 和 `job_id`，保存 PENDING/CREATED/REJECTED 及原请求、接入错误。每次创建/接入遵循 admission gate → parent → Job 的锁顺序，避免多个实例超越共享活动阈值，避免同键并发 UPSERT 的双唯一索引锁冲突。每次接入事务最多处理一个子项，创建 Job、绑定 batch_id、保存提交意图及子项关联原子提交。失败事务回滚后再以新事务记录对应 REJECTED，其他子项继续。验证和 Solver/RPC 都在事务外的已有可靠链路进行。

后台恢复不依赖内存 cursor：每秒查询最久未接入的最多四个父级，每个最多接入两个子项，总预算 8。重启后继续 PENDING 意图。后台重新加载持久化 actor 对应当前用户权限，Job 工件始终保持项目权限。

## 背压和公平性

未发送 Outbox `NEW/RETRY/PUBLISHING` 达 64，或活动 Job 达 32，暂停接入，不丢弃待处理意图。阈值可通过 `cae.batch.pending-limit/active-limit` 配置；客户端能看到等待原因与父级计数。其他用户手动提交仍由正常资源/许可证账本保护容量。

Outbox 每批默认 20、RabbitMQ prefetch 8、已有调度工作线程 4 与在途任务上限 20。每个学科有独立 License feature，Token 至少 1，所以默认结构最多 4、NVH/CRASH/CFD 各最多 2 个资源预约/使用执行；UNKNOWN 继续占用。许可证和 CPU/内存的原子防超卖保持原有锁顺序。

调度候选用 MySQL 8 `ROW_NUMBER() OVER(PARTITION BY analysis_type ...)`，按每学科队列位置交错提取最多 12 个。队列内 priority 加等待时间老化，避免大量高优先级结构任务遮蔽其他学科。取消任务单独提取最多 8 个。

## 父级真值与界面

父级计数从意图与实时 Job/Execution 关联派生：total/waiting/running/success/failed/cancelled，终态数/总数形成 progress。接入 REJECTED 计失败，尚未建立 Job 的 PENDING 计等待，UNKNOWN/CANCEL_REQUESTED/POST_PROCESSING/REPORTING 仍是进行中。所有完成后输出 COMPLETED/PARTIAL_FAILED/FAILED/CANCELLED；它们属于批次状态，不增加 Job 的 19 状态集合。

`GET /batches` 和 `/batches/{batchNo}` 按成员授权。Vue 批次页支持实际创建与刷新、子项链接、节点/PID、失败原因、计数与背压提示。输入变动更换创建键，超时保留原键。

## 验证入口

`BatchAdmissionIntegrationTest` 七项覆盖 24 个相同请求索引、并发去重、容量与 publisher confirm 后恢复、单项失败、学科公平、并发接入有界及唯一关联。测试度量其他套件保留行的基线，不修改其他套件数据；接入压力测试使用实际 MySQL 和 RabbitMQ。

`scripts/failure-demo.sh` → `tests/e2e/failure_smoke.py` 使用真实 Compose 运行规范的 12 场景。批次为 40 个真实子 Job，两个注入运行失败、一个缺失 INCLUDE，其余完成完整报告；观察暂停/恢复、三节点分布和父级部分失败。只恢复本脚本改变的节点 drain/heartbeat，保留历史队列、数据和执行记录。

## 压力验收发现的巡检竞态

首次 40 子任务验收中，巡检在 `EXECUTION_SUBMIT_REQUESTED` 发布前约 0.2 秒收到 request 查询 404，并把一个新 SUBMITTING 执行改成 UNKNOWN。消费者随后按状态跳过提交，使它长期占用预留。新修复对尚无远端身份的 SUBMITTING 执行延迟负面巡检结论，由原持久提交消费者负责有界重试；正面远端证据仍正常应用。已经确认的 SUBMITTED/RUNNING/UNKNOWN 的 404、离线和资源保留规则保持有效。

18 个巡检集成测试包括新回归，整个 Java 346 项通过。首次批次的已证明未发送子项通过原不可变 requestId 恢复，无新执行/无提前释放，最终 37 成功、3 预期失败；时序与身份证据见 `docs/validation/m10-race-recovery.json`。新的完整 12 场景运行用于验证修复后的自动路径。
