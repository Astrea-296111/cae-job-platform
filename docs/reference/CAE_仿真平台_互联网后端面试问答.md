# CAE 仿真平台｜互联网后端面试问答速记版（统一口径 + 团队协作）

> 用途：面试前快速背口径。数字以本文件第 1 节为唯一版本。  
> 原则：先讲互联网工程语言，再补 CAE 细节。

---

# 1. 统一数字卡 `[REFERENCE]`

```text
项目：2024-12-09 ～ 2025-07-25
团队：7 人
角色：组长 + 核心 Java 后端

Pilot：2025-06-02 ～ 2025-07-18，35 个工作日
授权用户：24
实际使用：21
专业组：4

纳管计算节点：8
Physical Cores：704
RAM：4 TB

Submit attempts：779
Validation blocked：47
Accepted actions：732
  Single：708
  Batch：24
Batch child jobs：720
Execution Jobs：1,428

Job 分布：
结构/模态 551
NVH      307
碰撞     268
CFD      302

Final outcome：
SUCCESS             1,340
CANCELLED              29
RUNTIME_FAILED          24
SUBMIT_FAILED           21
POST_PROCESS_FAILED     14

Baseline preventable defect：30/345 = 8.70%
Pilot preventable submit fail：21/732 = 2.87%

单任务：12.4 → 4.1 min（-66.9%）
50 Case Batch：47.6 → 5.8 min（-87.8%）
结构标准报告：86.4 → 18.2 min（-78.9%）
报告：32 份 = 扭转18 + 弯曲8 + 模态6

人工节省：
708×8.3 + 24×41.8 + 32×68.2 = 9,062 min
≈ 151.0 person-hours

Auto reconciled stale jobs：31
Duplicate submit blocked：19
Pilot feedback：63
Closed：56
Backlog：7
```

---

# 2. 30 秒项目定义

> 这是一个公司内部 CAE 长任务平台。公司本来就有计算服务器和 OptiStruct 等 Solver，我们做的是上面的业务控制面：工程师提交模型版本和工况，平台做校验、排队、节点选择、Solver 启动、状态跟踪、结果资产和标准报告。结构/模态是完整链路，NVH、碰撞、CFD 做不同深度的 Pilot。

---

# 3. 30 秒个人贡献

> 我是 7 人小组组长，同时负责核心 Java 后端。主要做 Job/Execution Domain、MySQL、状态机、RabbitMQ/Outbox、幂等 Submit、Node Heartbeat、资源预占和 Scheduler、Java 到 Python Agent 的接口以及 Reconciliation。专业 Parser/HyperView 脚本由专业成员和 Worker 同事主写，我负责 Contract 和联调。

---

# 4. 输入输出是什么？

输入：

```text
Project / Model Version
Discipline / Condition
Case Package / Entry Deck
SolverProfile
ResourceProfile
Priority
Baseline / ReportTemplate（可选）
```

同步返回：

```text
jobNo + status
```

异步最终输出：

```text
Job/Execution final state
Log
Result URI
Metric
Image / Curve
Report Draft
Failure Code
```

标准回答：

> 输入不是“一个 FEM 文件”，而是模型版本、工况和执行配置；输出也不是一个 HTTP Response，而是一个持续数小时演进的 Job 和最终结果资产。

---

# 5. 项目为什么做？

> Solver 能算，但周边流程碎片化。工程师要自己维护共享目录、服务器选择、脚本、状态、结果和 PPT。任务少时可接受，多车型、多专业和批量方案以后就出现路径错误、服务器热点、重复提交、版本难追踪和重复报告劳动。

---

# 6. 谁在用？真的上线了吗？

> 是内部 Pilot，不是公网 SaaS。Pilot 授权 24 人、实际使用 21 人；35 个工作日有 732 次有效提交动作，生成 1,428 个 Execution Job。结构/模态是完整 Reference Path，其他专业自动化深度不同。

---

# 7. 有前端后端吗？

```text
Vue 3：Job 创建、Batch、状态、Log、Result、Report
Spring Boot：业务控制面、状态机、调度、事务、MQ
Python Agent：Solver 环境适配
Python Post Worker：Parser / HyperView
```

---

# 8. 部署在哪里？

> 公司内网。Nginx + Spring Boot Docker；MySQL/Redis/RabbitMQ 在内部环境；Compute/Post Agent 在受控计算节点或执行网关；Solver 继续运行在原有 CAE Linux 环境，不为了项目强行把商业 Solver 容器化。

---

# 9. 为什么 Spring Boot 3.3.6 + Java 17？

> Java 17 是成熟 LTS。Spring Boot 3.3 在 2024-05 GA，3.3.6 在 2024-11 发布，所以 2024-12 启动的新内部项目用 3.3.6 很自然。控制面需要事务、Spring AMQP、Security、MySQL/Redis 生态；CAE 文件和后处理仍用 Python。

---

# 10. 为什么不微服务？

> 只有 7 人和几十个内部用户，微服务会增加部署、分布式事务和排障成本。采用模块化单体 + 独立 Worker/Agent，把真正需要运行环境隔离的计算适配拆出去。

---

# 11. 为什么低 QPS 还用 RabbitMQ？

> 因为复杂度来自“任务慢”，不是“用户多”。Solver 可能跑几个小时；Submit/Post/Report 需要异步解耦、Retry、DLQ 和 Backpressure。

---

# 12. MQ ACK 在哪里？

```text
Consumer 收 SUBMIT
→ Agent 接受启动
→ 返回 externalExecutionId
→ DB 持久化
→ ACK
```

> 不等 Solver 跑完才 ACK，否则 MQ 会被当成长任务运行时。

---

# 13. 最大技术难点

> 外部长任务的一致性。数据库事务包不住 Solver 进程：MQ 可能重投、Worker 可能在 Agent 已启动后宕机、状态事件也可能丢。最终采用 Outbox + 至少一次消息 + Idempotency-Key + DB CAS + Reconciliation。

---

# 14. 重复启动 Solver 的 Bug

```text
Agent submit 成功
→ Worker DB/ACK 前宕机
→ MQ Requeue
→ 再 submit
```

解决：

```text
UNIQUE(job_id, attempt_no)
CAS QUEUED→DISPATCHING
Idempotency-Key = job-{id}-attempt-{n}
Agent key→externalExecutionId
```

Pilot：`19` 次重复请求被挡住；修复后没有确认由 MQ 重投造成的重复 Solver Run。

---

# 15. 为什么还有 Reconciliation？

> 事件/网络不会 100% 可靠。每 120 秒扫描 RUNNING/UNKNOWN，按 externalExecutionId 向 Agent 对账。Pilot 自动修正 31 个 stale Job。

---

# 16. Node 掉线怎么办？

```text
Heartbeat 15s
45s → SUSPECT
90s → OFFLINE
```

> Agent 掉线不等于 Solver 停止，Job 先 UNKNOWN，不立即重跑，避免两个昂贵计算同时存在。

---

# 17. Scheduler 做什么？

不是 Slurm。

```text
Hard Filter:
online
solver capability
CPU/memory enough
storage reachable
license admission

Score:
free CPU/mem
running jobs
wait age
data locality
```

> 只管理 8 台 Pilot 节点的有限范围调度。

---

# 18. Resource Reservation 怎么防超卖？

> 不能先查再减。Redis Lua/DB CAS 原子预占；MySQL 记录 Reservation，可通过 Agent heartbeat/Reconciliation 恢复漂移。Redis 不是最终 Source of Truth。

---

# 19. MySQL 核心表

```text
cae_job
cae_job_execution
cae_job_event
cae_compute_node
cae_resource_reservation
cae_artifact
cae_result_metric
cae_report
cae_outbox_event
```

---

# 20. 为什么 Job 和 Execution 分表？

> Job 是业务任务；Execution 是一次真实 attempt。Retry 时创建新的 Execution，保留第一次失败历史、externalExecutionId 和时间线。

---

# 21. 状态机怎么保证并发正确？

```sql
UPDATE cae_job
SET status=?, version=version+1
WHERE id=? AND status=? AND version=?;
```

> Cancel/Submit/Monitor 并发时只有合法 CAS 成功，旧事件不能把新状态覆盖回去。

---

# 22. Outbox 为什么需要？

> 防止 DB 已 commit、MQ 没发出去。Job State + Outbox 在同一 MySQL 事务，后台 Publisher 发 RabbitMQ；Outbox 解决“不丢”，Consumer 幂等解决“可能重复”。

---

# 23. Redis 到底存什么？

```text
Node heartbeat/hot state
short reservation
idempotency/lock auxiliary data
```

> Job/Execution 事实在 MySQL。

---

# 24. `.hm` 和 `.fem` 区别

> `.hm` 是 HyperMesh 前处理源模型资产；OptiStruct 运行通常使用导出的 `.fem` Solver Deck。平台同时登记 source model 和 solver input，不把 `.hm` 当成运行入口。

---

# 25. 为什么 `.fem` 有了还会失败？

> 因为可能有 INCLUDE 指向个人目录/旧共享路径。平台做 CaseManifest、dependency inventory、path normalize、可选 `-check` 和 immutable input snapshot。

---

# 26. Altair 接口怎么用？

> OptiStruct CLI 启动求解，`-check/-inventory/-incpath` 做输入辅助校验；HyperView/HyperGraph Python/Tcl 自动加载结果、切 Subcase/Contour/View、截图；报告可以用 HyperWorks Report API 或 python-pptx 组装草稿。

---

# 27. Java 为什么不直接解析 H3D？

> Java 负责业务状态和 Metadata；商业结果格式交给 HyperView/专业 Python API。自己写 H3D binary parser 既不是项目核心，也会和版本强耦合。

---

# 28. 报告自动化到什么程度？

> 完整 Reference Path 只讲结构/模态标准报告。程序自动 KPI、Baseline 差异、标准截图/曲线和 PPTX 草稿；工程师审核并写最终原因分析。Crash/CFD 只做有限关键结果，不说全部自动签核。

---

# 29. 报告遇到过什么 Bug？

> KPI 是 Torsion Subcase，但图片脚本没绑定 Subcase，截到了别的工况。后来 PostProfile 增加 execution_id、subcase、frame、result_type、component、layer、view、selection_set，并在生成报告前校验 provenance。

---

# 30. 你和团队怎么协作？

> 按 Data Contract，不按“大家都改同一个 Service”。专业 Owner 给 JobTemplate、Golden Case、资源范围、失败码、Metric/Report 定义；我负责统一 Job/Execution/Artifact/Report Contract；Agent 同事消费 Execution Contract；前端只消费统一状态和错误码。

---

# 31. 协作颗粒度是什么？

```text
JobTemplate
CasePackage
Job
Execution
Artifact
Metric
ReportSpec
```

> 上游负责事实，下游负责实现；避免专业逻辑和平台状态机互相耦合。

---

# 32. 沟通节奏

```text
周一 09:30-10:00  7 人同步
周中按接口开小范围 Review
每 2~3 周真实 Case Milestone Demo
```

参加 Milestone：7 人 + 科室负责人 + 专业代表 + 必要时算力管理员。

---

# 33. 一个技术分歧故事

> 最初用户可以自由填 CPU/Memory。实际同一工况有人填 16C、有人 64C，调度不可控。最后专业 Owner 定 `Default + Allowed Range`，后端做版本化 Profile、校验和 Reservation，用户只在允许范围内微调。这个故事体现业务标准化和专业自由度的平衡。

---

# 34. 领导怎么验收？

最终 2025-07-18 演示 6 个场景：

```text
结构单 Job
50 Case Batch
重复 Submit 幂等
Node/Agent 异常恢复
Result Re-process + Report Regenerate
跨专业历史查询
```

> 验收不是看技术栈，而是看真实 Case 能不能跑、失败能不能恢复、结果能不能追溯、人工时间是否下降。

---

# 35. 部门反馈有哪些？

```text
结构：不要重新上传大文件 → NFS Path Registration
碰撞：Runtime Failed 不要无脑 Retry → 分类 Retry
CFD：优先 Batch → Parent/Child Job
报告审核：图片必须绑定 Subcase/Execution → Artifact Provenance
负责人：不要拿 QPS 做成果 → 看人工耗时/失败率
```

Pilot Feedback：63 条，最终关闭 56，7 条进 Backlog。

---

# 36. 数据是怎么来的？

统一口径：

```text
Baseline window：2024-10-14~11-29
345 次人工提交记录

Pilot：2025-06-02~07-18
779 submit attempts
732 accepted actions
1,428 execution jobs

报告样本：32 份结构标准报告
单任务耗时样本：40
50-case batch 样本：8
```

> 面试说“项目复盘统一统计口径”，不要说成财务审计。

---

# 37. 为什么 1,428 Job 不是 1,428 次提交？

```text
708 single actions → 708 jobs
24 batch actions → 720 child jobs
Total actions = 732
Total jobs = 1,428
```

这是修正总人工节省计算的关键。

---

# 38. 151.0 人时怎么算？

```text
Single：708×(12.4-4.1)/60 = 97.94 h
Batch：24×(47.6-5.8)/60 = 16.72 h
Report：32×(86.4-18.2)/60 = 36.37 h
Total = 151.03 h ≈ 151.0 h
```

> 分别按“提交动作”和“报告份数”统计，不拿 1,428 个 Child Job 重复乘。

---

# 39. 项目最大的失败设计

> MVP 曾让 Backend 直接中转大 Case 并更直接地控制服务器。很快发现 JVM 大文件链路和高权限远程执行都不合理，于是改成控制面只传 Metadata/Command，大文件走 NFS，节点侧 Agent 做受控执行。

---

# 40. 如果重做，优先改什么？

```text
1. Project/Vehicle 主数据对接公司统一系统
2. OpenTelemetry / execution trace
3. 更成熟的 Workflow / Temporal 评估
4. 结果知识库 / CAE Copilot（在结构化 Job/Result 数据之后）
```

不是第一优先级就上 K8s/分库分表。

---

# 41. 30 秒“最大难点”

> 最大难点是平台状态和外部 Solver 状态最终一致。Solver 运行几小时，DB 事务包不住外部进程；MQ 会重复、Agent 会断、事件会丢。我用 Outbox 保证命令不丢，用 Idempotency-Key/Execution Unique 避免重复外部副作用，用状态 CAS 处理 Cancel/Submit 竞态，再用 Reconciliation 做最终收敛。

---

# 42. 30 秒“团队协作”

> 我们按 CAE Job 生命周期拆模块。专业成员提供 JobTemplate、Golden Case、资源和结果定义，我负责把它们抽成统一 Job/Execution/Artifact/Report Contract；Python 同事按 Execution Contract 启动 Solver，前端按统一状态展示。每周固定同步，接口变化小范围 Review，每 2～3 周拿真实 Case 给负责人和专业代表验收。典型例子是 Resource Profile：专业侧决定默认值和可调整范围，后端负责版本、校验和资源预占。

---

# 43. 30 秒“项目效果”

> Pilot 35 个工作日，24 个授权用户、21 个实际用户，732 次有效提交动作生成 1,428 个执行 Job。单任务人工操作从 12.4 分钟降到 4.1 分钟，50 Case Batch 从 47.6 降到 5.8 分钟，可避免提交失败从 8.70% 降到 2.87%，32 份结构标准报告人工整理从 86.4 降到 18.2 分钟。按提交动作和报告份数分开核算，约减少 151.0 人时重复操作。

---

# 44. 面试时绝对不要混淆的边界

```text
公司已有算力 ≠ 我们自研 HPC
Scheduler ≠ Slurm 级通用调度器
24 users ≠ 高 QPS C端系统
1,428 Job ≠ 1,428 次人工提交
自动报告 ≠ 自动工程签核
四专业覆盖 ≠ 四专业后处理成熟度一样
Reference 数字 ≠ 公司财务审计
```


---

# 45. 技术时间线校准

```text
Spring Boot 3.3.0 GA：2024-05-23
Spring Boot 3.3.6：2024-11-21
RabbitMQ 3.13.0：2024-03-11
Redis 7.2：2023-08-15
HyperMesh Python API：HyperMesh 2024 首版
```

Altair 2024 官方能力与项目故事相符：OptiStruct `.fem` 命令行、`-check/-inventory/-incpath`，HyperView Python 自动截图，以及 Report API 生成 PPTX。

面试方向来自公开后端面经的共性：项目背景、Ownership、上线和用户、部署、难点、数据指标、Redis/MySQL/MQ 的真实使用、系统瓶颈和重构思路。
