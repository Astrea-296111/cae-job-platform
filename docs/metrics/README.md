# 指标量化规范与验收设计

> 文档状态：设计草稿。本文重构指标定义与验证方式，不声明性能实现已经完成。
> 所有目标数值见 [targets.example.json](targets.example.json)，状态均为 TARGET_NOT_MEASURED。
> 现有实测记录见 [benchmark.md](../benchmark.md)；不能用目标值替换原始记录。

## 1. 项目边界与证据

项目是既有计算资源之上的 Java 控制面、输入治理、执行一致性及结果/报告编排。公开代码使用 Mock Solver 和合成结果；商业求解提速、真实集群 CPU 利用率、企业试运行规模与个人历史职责不能由本仓库推导。

| 类型 | 可表达的内容 | 状态 |
| --- | --- | --- |
| 当前实现 | 代码中可走读的 Job/Execution、Outbox、幂等、资源账本、输入快照和阶段重试 | CODE |
| 既有基准 | 2026-10-12 原始样本及其分位数 | RECORDED_LOCAL_BENCH；本次未重跑 |
| 本文性能目标 | 固定条件下待验证的优秀表现 | TARGET_NOT_MEASURED |
| 报告人工收益假设 | 用于设计配对计时的 60→15 分钟情景 | ILLUSTRATIVE_ASSUMPTION |
| 新验证结果 | 只有实际运行、保存原始样本、说明版本后才能填写 | 当前为空 |

## 2. 统一统计对象

- Submission action：一次用户操作；批次作为一次动作。
- Batch intent item：批次事务中持久化的一份子任务意图，不等于已创建的 Job。
- Job：业务任务；按 Job id 去重统计。
- Execution：一次明确的执行尝试；重试是不同 attempt，消息重投不是新的 Execution。
- External process：节点实际启动的进程，以稳定外部身份及启动证据核验。
- Report version：同一任务可以有多个报告版本；版本行数不等于成功报告的任务数。

业务规模只作背景，性能、可靠性、输入治理和人工收益分别验证。所有率使用明示的分子、分母、窗口；批次失败、子任务失败和执行失败不能混用。

## 3. 核心指标契约

| 指标 | 边界及计算 | 证据与归属 |
| --- | --- | --- |
| Job 详情/创建接口 P95 | 客户端单调时钟，请求开始至完整响应，包含本基准的建连成本；成功与错误延迟分开，错误数量全部报告 | 请求级 CSV/JSONL；Java API、SQL、缓存及连接等待 |
| 50-Case 批次受理 P95 | 返回成功且 Batch 和 50 个 intent item 的事务已提交；不包括子 Job 的校验、排队与求解 | 批次响应 + DB 行数/关联检查；Java 事务与批量编排 |
| 子任务接入时间 | batch.created_at→各子 Job.created_at；另列最后一个子任务接入时间 | 100 个子任务延迟样本不是 100 次批次 API 请求 |
| 派发耗时 | 资源预占成功→Agent 持久受理；记录冻结的 executionRequestId | 分阶段事件；现有 submit histogram 范围与此不完全相同，不能直接改名 |
| 排队耗时 | 入队→节点确认实际开始；资源不足及 License 等待须单列原因 | 逐 Execution 事件；同端计时优先，跨主机时间须验证时钟与事件语义 |
| 重放新增进程数 | covered replay attempts 中除原始执行之外的真实进程启动数 | Agent 登记 + 受控启动计数/日志；不能只数 Java 行或 HTTP 200 |
| 故障恢复后状态收敛 | 恢复条件就绪→MySQL 状态与独立查询 Agent 事实一致 | 同时报告故障持续时间、未恢复和超时；不是端到端可用性或全局 MTTR |
| 非法输入检出 | 正确拒绝非法样本数 / 全部人工标注非法样本数 | 五类缺陷 fixture，与合法样本误拦配对报告 |
| 合法输入误拦 | 被拒绝的合法样本数 / 全部人工标注合法样本数 | 同一固定 fixture 集；不是企业提交失败率 |
| 报告人工整理耗时 | 相同内容、视图、单位与审核要求下，人员实际作业时间 | 人工计时表；Java 编排与 Python/专业脚本共同成果 |

低样本量优先报告原始值、中位数和范围，不包装成可靠的 P99。百分位使用最近秩 ceil(n×p)，并保留分布和样本数。中位数之差乘次数只可作明示的粗估，不能称实际累计人时；需总收益时对每一对记录分别求差再求和。

## 4. 同条件性能验证

目标环境：单环境总 CPU 配额 4 核、总内存 32 GiB；中间件、后端和 Mock Worker 共用资源。预置 10,000 条合成 Job 元数据。存储驱动、认证、网络路径、连接复用策略、日志级别与依赖版本必须记录。

接口阶段与执行阶段分开运行：
1. 接口阶段暂停后台 feeder/consumer，验证接口受理的持久化正确性。读取、创建、批次三个负载分别使用 20、20、5 个并发客户端。每项预热 200 请求，正式样本分别 2,000、1,000、100 个，独立重复三轮；每轮单列结果。
2. 批次每个 50 个 intent，payload 不超过 256 KiB。分别核对事务后 item 数、父子关系、幂等返回及隔离环境的清理边界。
3. 执行阶段开启后台链路，使用受控 Mock 时长，记录子任务接入、资源等待、派发、Solver、后处理和报告等阶段。不能用接口阶段的结果代替端到端结果。
4. API 错误、业务容量拒绝、校验失败、未知失败均单列。容量拒绝不能算系统异常，也不能算一次成功 Solver 执行。
5. 两个版本在同一策略下重新运行；旧记录未预热且负载不同，不能直接用旧值计算优化百分比。
6. 当前 example JSON 仅为规划配置，现有 make benchmark 不会自动加载它；接入 runner、数据生成器和校验器属于后续实现。

每个请求记录 run_id、commit、workload、request_id、样本大小、并发、起止时间、HTTP/业务结果。每个执行事件另记 job_id、execution_id、attempt、execution_request_id 和阶段；高基数标识保留在原始证据中，不作为 Prometheus labels。

## 5. 可靠性与输入验收样本

### 重放目标
20 个不同的有效 executionRequestId，各先提交一次，再各重放 25 次，共 520 请求，其中重复请求 500 次。记录原始进程与每次重放的返回身份；目标为原始启动 20 次、重放新增启动 0 次。可分批运行，不能假定节点可同时准入 20 个求解任务。

该目标只覆盖已形成持久外部执行身份的场景。不宣称解决所有 claim/Popen 崩溃窗口，不把未知进程和保留容量静默改成成功。

### 状态收敛目标
三类场景各十轮，共三十轮：
- Agent 已受理并保持运行时，Java 后端重启；
- Agent 已受理后提交消息重投；
- 完成回调丢失，但 Agent 的查询证据仍可获得。

每轮先明确恢复条件就绪的时间，再记录状态一致时间。目标 P95≤15 秒；30 轮均需恢复，超时不能从分位数中丢掉。完整故障持续时间另报。Agent/节点不可恢复、UNKNOWN、DEAD/DLQ 等情况保留其失败或人工处理状态。

### 输入回归目标
120 组预先标注的合成 Case：80 合法、40 非法。非法集为入口缺失、INCLUDE 缺失、manifest 依赖不一致、不安全相对路径、声明 checksum 不匹配，各 8 例。目标正确拦截 40/40，合法误拦 0/80；这是有限 fixture 的验收目标，不能称生产环境 100% 检出。

样本和生成器尚未在本次新增。标签须依据输入契约独立建立，不能由被测校验器自己的返回值生成正确答案。

## 6. 人工报告的情景假设

设计 30 组配对样本，扭转、弯曲、模态各 10 组。固定同一结构结果、报告页数/视图、工程师熟练度和审核要求，交错安排两种流程，记录实际作业及机器等待的独立时间。

仅为说明测量方案，假设人工中位耗时 60 分钟，期望平台辅助后≤15 分钟，情景差值 45 分钟、降幅 75%。这不是当前基线，不表示已开展 30 组实验；真实报告、计时和专业审核均待获得。公开合成 PPTX 的生成耗时不能证明人工节省。

## 7. 当前代码与待补部分

| 范围 | 已有事实 | 后续工作，尚未实现 |
| --- | --- | --- |
| PlatformMetrics | 排队统计为 SQL AVG；资源与报告数据库行数为 Gauge | 逐阶段样本、窗口化分布及合适的 histogram |
| BatchFeedService | 每批每轮至多 2 个子任务、默认 fixedDelay 1 秒并受背压限制 | 按预算配置接入量与公平策略后，单独验证接入和端到端耗时 |
| 执行一致性 | Job/Execution、Outbox、节点登记与对账 | 为外部启动及恢复事件建立独立试验账本与超时断言 |
| 输入治理 | 有依赖、路径、manifest 与 snapshot/checksum 校验 | 五类 labelled fixture、合法对照集及防误拦报告 |
| benchmark.py | 已测 HTTP、Batch、SSE 与部分 Java driver | 可配置负载、三轮重复、target evaluator、失败样本完整输出 |
| 简历材料 | 有可按源码解释的技术设计 | 个人职责确认与实际实验结果后替换目标文字 |

当前代码路径：
- [BatchService](../../backend/src/main/java/com/example/cae/batch/BatchService.java)
- [BatchFeedService](../../backend/src/main/java/com/example/cae/batch/BatchFeedService.java)
- [PlatformMetrics](../../backend/src/main/java/com/example/cae/observability/PlatformMetrics.java)
- [InputSnapshotService](../../backend/src/main/java/com/example/cae/job/validation/InputSnapshotService.java)
- [已有 benchmark](../../tests/e2e/benchmark.py)

## 8. 简历使用与依据

[简历替换稿](resume-drafts.md) 提供基于现有能力的文字稿及每条明确标注目标的演示稿。只有能回溯 run_id、版本、样本和完整结果的数字，才能改成已取得成果。个人职责以本人确认的实际工作为边界。

指标依据：
- [Airflow metrics](https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/logging-monitoring/metrics.html)：排队、运行、调度分开。
- [美团流水线引擎](https://tech.meituan.com/2022/07/14/CICD-Pipeline.html)：平台成功与业务/第三方失败分开。
- [Google SRE monitoring](https://sre.google/sre-book/monitoring-distributed-systems/)：时延、流量、错误与饱和度。
- [Anthropic Agent evals](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)：固定任务集中的质量、时延、用量及成本。
- [Tool Search](https://www.anthropic.com/engineering/advanced-tool-use)：初始工具上下文与按需发现的取舍。

以上来源支持指标选择，不是这里 120/400/300 ms、15 秒或 Agent 60% 目标的行业达标线。目标由本次工程规划提出，是否可达须以实际实验确认。
