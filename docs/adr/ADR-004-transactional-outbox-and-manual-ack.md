# ADR-004: Transactional Outbox 与短生命周期 MQ Consumer

状态：已实施（Milestone 5）。

## 问题

数据库提交与 RabbitMQ 发布不能组成一个本地事务。更新 Job 后立即发送 MQ，可能在进程退出时丢失命令；发送后再提交数据库，则可能让 Consumer 看到尚未提交的执行记录。Solver 是独立的长时间进程，不能占用消息 Consumer 直到计算结束。

## 决策

JobRepository 在修改 Job 和插入 JobEvent 的同一事务中调用 `OutboxRepository.appendJobEvent`。该方法要求已有事务，并将事件及必要的命令写入 MySQL。Publisher 通过 MySQL 8 `FOR UPDATE SKIP LOCKED` 获取有期限的发布租约，事务提交后才调用 RabbitMQ。仅在 correlated Publisher Confirm 为 ACK、mandatory publication 没有 return 时，将相同租约拥有者的记录标为 SENT。

队列消费分成两步：`JOB_SUBMIT_REQUESTED` 完成资源分配；分配事务内的 `EXECUTION_ALLOCATED` 产生 `EXECUTION_SUBMIT_REQUESTED`。后一条命令使用持久化的 executionRequestId 调用 Agent，保存 Agent 的接收结果，然后完成 Inbox 并手动 ACK。Consumer 不等待 Solver 完成。

Inbox 唯一键为 `(logical_event_id, consumer_name)`。业务处理租约过期后允许重试；重复已完成的逻辑命令直接 ACK。相同逻辑命令的并发投递进入持久化的延迟 Outbox，不立即 requeue。HTTP 超时等不确定结果采用同一 executionRequestId 重试。新的物理投递使用新的 deliveryId，业务 eventId 保持相同。

Inbox claim 通过 `INSERT ... ON DUPLICATE KEY UPDATE id=id` 直接取得唯一行排他锁，重复分支保持既有业务字段。随后检查租约，避免 `INSERT IGNORE` 共享锁升级导致并发重复投递死锁。

## 失败行为

- 发布失败：Outbox 的 `retry_count` 与 `next_retry_time` 持久化指数退避；达到上限标记数据库 DEAD，保留可检查的错误。
- 业务接收失败：Inbox RETRY 与新的延迟 Outbox 在同一事务提交，然后 ACK 旧投递。
- 重试耗尽：Inbox DEAD 提交后 `basicReject(requeue=false)`，由 RabbitMQ dead-letter exchange 路由至阶段 DLQ。提交仍不确定时 Job/Execution 进入 UNKNOWN，资源继续保留，等待远端对账。
- 数据库暂不可用：原消息保持 unacked，Consumer 按 250ms 至 5s 的有界指数退避暂停，再 requeue。没有 ACK，也没有凭空更改 Job 或资源。
- 消费者在业务提交后、ACK 前退出：RabbitMQ 重投，Inbox DONE 或已保存的 Execution 状态阻止重复业务执行。
- Publisher 在 broker ACK 后、更新 SENT 前退出：发布租约到期后允许重复发布，由 Inbox 和 Agent 的持久化幂等保护执行。

## 结果与边界

交付保证为 **at-least-once delivery + idempotent business handling**。数据库、RabbitMQ 和进程启动之间没有 Exactly Once 保证；Agent 使用 SQLite 唯一键和独立进程身份补足执行面的幂等与恢复。

Redis 不参与 Outbox、Inbox 或资源正确性。解析、后处理和报告队列及 DLQ 已真实声明；只有相应业务处理器实施完成后才安装 Consumer。事件额外发布至持久化 `cae.event.audit`，Milestone 5 保留这些事件；JobEvent 数据库日志仍为事件查询和后续 SSE 恢复的权威来源。

## 可检查代码

- `backend/src/main/java/com/example/cae/job/repository/JobRepository.java`：Job CAS、JobEvent 和 Outbox 同事务。
- `backend/src/main/java/com/example/cae/outbox/OutboxRepository.java`：短发布租约及拥有者检查。
- `backend/src/main/java/com/example/cae/outbox/OutboxPublisher.java`：Confirm、mandatory return 和持久化发布重试。
- `backend/src/main/java/com/example/cae/messaging/InboxRepository.java`：逻辑幂等、延迟重投和失败事务。
- `backend/src/main/java/com/example/cae/messaging/SubmitCommandListener.java`：实际手动 ACK 与短接收流程。
