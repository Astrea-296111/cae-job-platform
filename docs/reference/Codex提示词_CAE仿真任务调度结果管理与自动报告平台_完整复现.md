# Codex 总提示词：完整复现「CAE 仿真任务调度、结果管理与自动报告平台」

> 使用方式：将本文件完整内容作为 Codex 的主任务提示词使用。建议从一个空 Git 仓库开始执行。
>
> 目标不是做一个“展示型 Demo”，而是构建一个**可运行、可测试、可故障注入、可通过 Docker Compose 一键启动、可用于后端实习面试演示**的公开复现项目。
>
> 项目原型来源于车企 CAE 科室内部提效场景。公开复现必须与真实企业环境解耦：不得依赖公司数据、公司服务器、商业 Solver License 或不可公开资产。所有商业软件/公司基础设施使用 Adapter 保留扩展点，默认使用 Mock/Synthetic 实现。

---

# 0. 你的角色

你是这个项目的**主程 / Tech Lead / Codex 实施工程师**。

你需要自行完成：

1. 需求拆解；
2. 系统架构；
3. 数据库设计；
4. Java 后端；
5. Python Compute/Post Worker；
6. Mock Compute Node Pool；
7. Mock CAE Solver；
8. Vue 3 前端；
9. RabbitMQ / Redis / MySQL 集成；
10. SSE 实时状态；
11. 结果解析；
12. 报告自动生成；
13. Docker Compose；
14. Prometheus/Grafana 基础监控；
15. 单元测试、集成测试、故障注入测试；
16. Demo 数据；
17. README、架构文档、状态机文档、API 文档、面试演示文档。

除非遇到真正无法通过代码解决的外部依赖，否则**不要停下来问用户下一步做什么**。合理选择默认方案并继续实施。

如果存在多种可行方案，优先选择：

```text
可运行 > 可测试 > 易解释 > 工程合理 > 技术炫技
```

禁止为了“技术栈丰富”引入无必要组件。

---

# 1. 项目一句话定义

实现一个面向车企 CAE 科室的内部工程计算平台：

```text
工程师创建仿真任务
→ 模型/Case 校验
→ 资源准入与节点调度
→ 异步提交 Compute Agent
→ Mock Solver 长时间运行
→ 状态跟踪与失败恢复
→ 结果解析
→ 自动后处理
→ 自动生成标准仿真报告
→ 历史检索与版本对比
```

本项目不是实现有限元/CFD 数值算法，也不是重新造 Slurm/PBS。

本项目实现的是已有计算服务器和 Solver 之上的：

> **CAE Compute Control Plane + Result/Report Pipeline**

---

# 2. 项目背景与业务故事

企业已经有：

```text
CAE 计算服务器
共享工程存储
商业 Solver
Solver License
HyperMesh / HyperView 等工程软件
成熟 CAE 建模规范
```

但原来的工作方式大量依赖：

```text
共享目录
个人脚本
SSH/Linux 命令
Excel 登记
人工选服务器
人工查运行状态
人工找结果文件
人工抄 KPI
人工 HyperView 截图
人工填 PPT/报告
```

不同专业包括：

```text
结构刚度 / 模态
NVH
碰撞安全
CFD / 风阻
```

虽然物理问题不同，但都有相同的任务生命周期：

```text
准备输入
→ 校验
→ 提交
→ 排队
→ 运行
→ 失败/成功
→ 结果整理
→ 报告
```

因此平台抽象统一 `CAE Job`，把专业差异放到：

```text
SolverAdapter
ResultParser
PostProcessor
ResourceProfile
ReportSpec
```

---

# 3. 公开复现的真实性边界

必须遵循以下成熟度：

```text
结构刚度/模态  [CORE]    完整端到端 + 标准报告
NVH             [PILOT]   Job/资源/状态 + 少量标准指标
碰撞安全        [PILOT]   Job/资源/状态/结果资产 + 少量关键字段
CFD/风阻         [PILOT]   Job/资源/状态 + Cd/Cl/Convergence
```

不要把四个专业全部做成同等深度。

公开版不得声称：

```text
已经接真实 OptiStruct
已经接真实 LS-DYNA
已经接真实 HyperView
已经管理公司真实服务器
已经使用真实 Solver License
```

应该实现：

```text
MockOptiStructAdapter
MockCrashAdapter
MockCfdAdapter
MockHyperViewPostProcessor
```

并保留真实 Adapter SPI，例如：

```text
OptiStructAdapter       # interface / placeholder
HyperViewAdapter        # interface / placeholder
BatchSchedulerAdapter   # interface / placeholder
```

但这些默认不得要求商业软件才能运行测试。

---

# 4. 时间对应的技术栈

项目故事时间：

```text
2024.12 - 2025.07
```

公开复现默认使用：

## 后端

```text
Java 17
Spring Boot 3.3.x
Spring MVC
Spring Security
Spring AMQP
Spring Validation
MyBatis-Plus
MySQL 8
Redis 7
Redisson
RabbitMQ 3.x
SSE
Spring Scheduling
Micrometer
Prometheus
Docker
Nginx
```

## Worker

```text
Python 3.11+
FastAPI
Pydantic
psutil
requests/httpx
matplotlib
python-pptx
Pillow
```

## 前端

```text
Vue 3
TypeScript
Vite
Pinia
Vue Router
Axios
Element Plus（或同级成熟组件库，保持一致）
ECharts
```

## 工程

```text
Maven
Docker Compose
JUnit 5
Testcontainers（优先用于关键集成测试）
pytest
GitHub Actions（可选但推荐）
```

禁止为了简历引入：

```text
Spring Cloud 全家桶
Kubernetes
Kafka
Elasticsearch
Seata
Temporal
```

除非只在 `docs/future.md` 作为未来演进说明，不得成为运行依赖。

---

# 5. 总体架构

实现以下逻辑架构：

```text
                       ┌─────────────────────┐
                       │       Vue 3         │
                       │ Task / Dashboard    │
                       └──────────┬──────────┘
                                  │ HTTPS / SSE
                                  ↓
                       ┌─────────────────────┐
                       │       Nginx         │
                       └──────────┬──────────┘
                                  ↓
                ┌────────────────────────────────┐
                │ Spring Boot 3.3 / Java 17      │
                │                                │
                │ Project / Job                  │
                │ Scheduler / Resource           │
                │ Artifact / Result / Report     │
                │ Auth / Audit / Outbox          │
                └───────┬────────┬────────┬──────┘
                        │        │        │
                     MySQL     Redis    Shared FS
                        │        │        │
                        └──── RabbitMQ ──┘
                                  │
                                  ↓
                       ┌────────────────────┐
                       │ Python Node Agent  │
                       │ Compute/Post Worker│
                       └──────────┬─────────┘
                                  │
                           DirectAgentAdapter
                                  │
                         Mock Compute Nodes
                                  │
                            Mock Solver
                                  │
                      Result / Log / Artifact
                                  │
                             Callback/Poll
                                  │
                             Java Backend
```

公开版“Shared FS”可以使用 Docker Volume 模拟 NFS。

MinIO 可作为派生小资产存储：

```text
报告
PNG
曲线图片
导出的 JSON/CSV
```

但 Solver 原始 workspace 必须保留在共享文件系统语义中，不能让大结果强行经过 Java JVM 中转。

---

# 6. 仓库目录必须至少达到下面结构

```text
cae-job-platform/
├─ backend/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/com/example/cae/
│     │  ├─ common/
│     │  ├─ auth/
│     │  ├─ project/
│     │  ├─ job/
│     │  │  ├─ api/
│     │  │  ├─ application/
│     │  │  ├─ domain/
│     │  │  ├─ infrastructure/
│     │  │  └─ repository/
│     │  ├─ scheduler/
│     │  ├─ resource/
│     │  ├─ execution/
│     │  ├─ artifact/
│     │  ├─ result/
│     │  ├─ report/
│     │  ├─ outbox/
│     │  ├─ audit/
│     │  └─ monitoring/
│     └─ test/
│
├─ worker/
│  ├─ app/
│  ├─ adapters/
│  ├─ agents/
│  ├─ mock_solver/
│  ├─ parser/
│  ├─ postprocess/
│  ├─ report/
│  └─ tests/
│
├─ frontend/
│  ├─ src/
│  └─ ...
│
├─ demo-data/
│  ├─ structure/
│  ├─ nvh/
│  ├─ crash/
│  └─ cfd/
│
├─ deploy/
│  ├─ docker-compose.yml
│  ├─ nginx/
│  ├─ prometheus/
│  └─ grafana/
│
├─ scripts/
│  ├─ bootstrap.sh
│  ├─ demo.sh
│  ├─ failure-demo.sh
│  └─ benchmark.sh
│
├─ docs/
│  ├─ architecture.md
│  ├─ domain-model.md
│  ├─ state-machine.md
│  ├─ api.md
│  ├─ mq-and-outbox.md
│  ├─ scheduler.md
│  ├─ case-package.md
│  ├─ report-pipeline.md
│  ├─ failure-injection.md
│  ├─ benchmark.md
│  ├─ interview-demo.md
│  └─ future.md
│
├─ .github/workflows/
├─ README.md
└─ Makefile
```

如果你认为单 Maven Module 比多个 Module 更容易完成和维护，允许使用模块化单体单 Module，但 Java Package 边界必须清楚。

**不要为了模拟“企业项目”拆成十几个微服务。**

---

# 7. 核心领域模型

至少实现：

```text
Project
Vehicle/Model
ModelRevision
AnalysisType
Condition/LoadCase
CaeJob
JobExecution
Artifact
ResultMetric
ResultParseRecord
Report
ReportTemplate
ComputeNode
ResourceReservation
JobEvent
OutboxEvent
AuditLog
```

## 7.1 `cae_job`

必须至少有：

```text
id
job_no
project_id
model_revision_id
analysis_type
condition_code
solver_type
solver_profile
solver_profile_version
priority
status
stage
submitter_id
input_manifest_uri
input_checksum
resource_profile_id
current_execution_id
failure_code
failure_message
version                # optimistic lock/status CAS
created_at
updated_at
submitted_at
started_at
finished_at
```

## 7.2 `cae_job_execution`

一个 Job 可以多次执行：

```text
id
job_id
attempt_no
external_execution_id
node_id
status
submit_request_id
process_identity
workspace
exit_code
started_at
finished_at
created_at
```

约束：

```text
UNIQUE(job_id, attempt_no)
UNIQUE(submit_request_id)
UNIQUE(external_execution_id) where appropriate
```

## 7.3 `cae_artifact`

```text
id
job_id
execution_id
artifact_type
uri
file_name
size_bytes
checksum
content_type
created_at
```

Artifact Type 示例：

```text
INPUT_DECK
INCLUDE
SOLVER_LOG
RAW_RESULT
RESULT_JSON
RESULT_CSV
IMAGE
CURVE
REPORT_PPTX
REPORT_PDF
```

## 7.4 `result_metric`

不要把所有专业结果硬塞一张宽表。

采用 Canonical Metric：

```text
id
job_id
parser_version
metric_code
metric_name
value_num
value_text
unit
dimension
status
created_at
```

例如：

```text
TORSIONAL_STIFFNESS = 21540 N*m/deg
MODE_1_FREQUENCY    = 28.6 Hz
CFD_CD              = 0.278
CRASH_INTRUSION_MAX = 142.3 mm
```

## 7.5 `cae_job_event`

保存业务时间线：

```text
JOB_CREATED
VALIDATION_STARTED
VALIDATION_FAILED
JOB_READY
RESOURCE_RESERVED
SUBMIT_REQUESTED
AGENT_ACCEPTED
SOLVER_RUNNING
SOLVER_SUCCESS
SOLVER_FAILED
POST_STARTED
RESULT_READY
REPORT_READY
JOB_CANCELLED
```

事件表不是 MQ，而是业务审计历史。

---

# 8. Job 状态机

必须实现集中状态机，不允许在不同 Service 到处：

```java
job.setStatus(...)
```

状态至少包含：

```text
CREATED
VALIDATING
READY
WAITING_RESOURCE
QUEUED
SUBMITTING
SUBMITTED
RUNNING
POST_PROCESSING
REPORTING
SUCCESS
VALIDATION_FAILED
SUBMIT_FAILED
RUNTIME_FAILED
POST_PROCESS_FAILED
REPORT_FAILED
CANCEL_REQUESTED
CANCELLED
UNKNOWN
```

必须有：

```java
public interface JobTransitionPolicy {
    boolean canTransit(JobStatus from, JobStatus to);
}
```

并由领域对象执行：

```java
job.transitTo(target);
```

数据库更新必须体现 CAS/Optimistic Lock，例如：

```sql
UPDATE cae_job
SET status = ?, version = version + 1, updated_at = NOW()
WHERE id = ?
  AND status = ?
  AND version = ?;
```

更新行数为 0 必须视为并发冲突，不能静默覆盖。

必须为状态机写完整单元测试：

```text
合法迁移通过
非法迁移拒绝
终态不能随意回 RUNNING
CANCEL 与 RUNNING 竞态
重复 SUCCESS 事件幂等
乱序事件不倒退状态
```

---

# 9. API

至少实现以下 API。

## 项目/模型

```http
POST /api/v1/projects
GET  /api/v1/projects
POST /api/v1/models
POST /api/v1/model-revisions
```

## Job

```http
POST /api/v1/jobs
POST /api/v1/jobs/{jobNo}/validate
POST /api/v1/jobs/{jobNo}/submit
GET  /api/v1/jobs/{jobNo}
GET  /api/v1/jobs
POST /api/v1/jobs/{jobNo}/cancel
POST /api/v1/jobs/{jobNo}/retry
GET  /api/v1/jobs/{jobNo}/events
GET  /api/v1/jobs/{jobNo}/artifacts
GET  /api/v1/jobs/{jobNo}/metrics
```

## Batch

```http
POST /api/v1/batches
GET  /api/v1/batches/{batchNo}
```

Batch 使用 Parent + Child Job 模型。

## Report

```http
POST /api/v1/jobs/{jobNo}/reports
GET  /api/v1/jobs/{jobNo}/reports
GET  /api/v1/reports/{reportId}/download
```

## Compute Node

```http
GET /api/v1/compute-nodes
GET /api/v1/compute-nodes/{nodeId}
```

## SSE

```http
GET /api/v1/events/stream
```

允许支持 query：

```text
jobNo
projectId
```

SSE 是通知机制，不是 Source of Truth。页面重连后必须重新 GET Job 状态。

---

# 10. 创建 Job 的输入输出

POST `/api/v1/jobs` 示例：

```json
{
  "projectId": 1,
  "modelRevisionId": 12,
  "analysisType": "STRUCTURE",
  "conditionCode": "BIW_TORSION",
  "solverType": "MOCK_OPTISTRUCT",
  "solverProfile": "STRUCTURE_STANDARD",
  "priority": 50,
  "input": {
    "entryDeck": "demo-data/structure/case-001/model.fem",
    "manifest": "demo-data/structure/case-001/manifest.json"
  },
  "resource": {
    "cpu": 8,
    "memoryGb": 16,
    "walltimeMin": 30
  }
}
```

同步响应：

```http
202 Accepted
```

```json
{
  "jobNo": "CAE-20250618-000013",
  "status": "CREATED",
  "links": {
    "self": "/api/v1/jobs/CAE-20250618-000013",
    "events": "/api/v1/jobs/CAE-20250618-000013/events"
  }
}
```

最终异步输出包括：

```text
Job Status
Execution History
Solver Log
Raw Result
Structured Metrics
Images / Curves
PPTX Report
```

---

# 11. Transactional Outbox

必须真正实现，不允许只在 README 描述。

表：

```sql
CREATE TABLE cae_outbox_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NULL,
    created_at DATETIME NOT NULL,
    published_at DATETIME NULL,
    UNIQUE KEY uk_event_id(event_id),
    KEY idx_status_retry(status, next_retry_time)
);
```

业务事务：

```text
更新 Job
+ 写 Job Event
+ 写 Outbox Event
COMMIT
```

Publisher：

```text
claim NEW event
→ publish RabbitMQ
→ Publisher Confirm
→ mark SENT
```

多实例优先采用 MySQL 8：

```sql
SELECT ... FOR UPDATE SKIP LOCKED
```

即使重复 publish，Consumer 仍必须幂等。

不要声称 Exactly Once。

正确口径：

> At-least-once delivery + idempotent business handling.

---

# 12. RabbitMQ

至少创建：

```text
exchange: cae.command
exchange: cae.event

queue: cae.job.submit
queue: cae.job.postprocess
queue: cae.job.report

DLQ:
cae.job.submit.dlq
cae.job.postprocess.dlq
cae.job.report.dlq
```

消息示例：

```json
{
  "eventId": "evt-uuid",
  "eventType": "JOB_SUBMIT_REQUESTED",
  "jobId": 871233,
  "jobNo": "CAE-20250618-0013",
  "occurredAt": "2025-06-18T10:01:23Z",
  "payloadVersion": 1
}
```

必须使用：

```text
Publisher Confirm
Manual Ack
DLQ
retry_count
exponential backoff / scheduled retry（可通过 DB next_retry_time 实现）
```

关键原则：

> MQ Consumer 不能等 Mock Solver sleep 30 秒/几分钟以后才 ACK。

正确过程：

```text
consume submit command
→ 调 Agent submit
→ Agent 建立 Execution 并返回 externalExecutionId
→ Java 持久化 Execution / SUBMITTED
→ ACK RabbitMQ
→ Solver 后续运行由 Agent/Monitor 跟踪
```

这是必须通过测试验证的行为。

---

# 13. 幂等与重复提交

实现两层幂等。

## Java 控制面

`submit_request_id` 或 `idempotency_key`：

```text
Job + submit attempt
```

数据库必须有唯一约束。

## Agent 执行面

Agent 接收到相同：

```text
executionRequestId
```

必须返回原来的：

```text
externalExecutionId
```

不能第二次启动 Mock Solver。

必须写集成测试模拟：

```text
Agent 已启动 Solver
→ Java 请求超时
→ RabbitMQ 重投
→ 第二次 submit
```

验收结果：

```text
只产生一个真实 Solver Process / execution
```

这是整个项目最重要的故障注入之一。

---

# 14. Redis 使用边界

Redis 用于：

```text
热 Job 查询缓存
短期 Idempotency Key
Compute Node 状态快照
Resource Admission 原子操作
短期分布式锁
SSE 在线 session 辅助
```

Redis 不允许作为：

```text
Job 最终状态唯一存储
Execution 唯一事实来源
Raw Result 存储
```

Source of Truth：

```text
Job Business State → MySQL
Remote Runtime      → Node Agent / runtime
Raw Result          → Shared FS
Cache/Snapshot      → Redis
Message Transit     → RabbitMQ
```

---

# 15. Compute Node Registry 与 Heartbeat

Mock 至少创建 3 个节点：

```text
node-a: 16 CPU / 32 GB / STRUCTURE,NVH
node-b: 32 CPU / 64 GB / STRUCTURE,CRASH
node-c: 24 CPU / 48 GB / CFD,STRUCTURE
```

Node 数据：

```text
nodeId
hostname
online
cpuTotal
cpuUsed
cpuReserved
memoryTotalGb
memoryUsedGb
memoryReservedGb
solverTags
runningJobs
lastHeartbeat
```

Python Agent 每 5 秒心跳：

```http
POST /internal/v1/agents/{nodeId}/heartbeat
```

后端超过例如 20 秒没有心跳：

```text
ONLINE → SUSPECT → OFFLINE
```

不要立即把节点上所有 Job 自动重跑。

节点失联时 Job 进入：

```text
UNKNOWN
```

然后由 Reconciliation 决定。

---

# 16. Scheduler

不要实现复杂 HPC Scheduler。

必须实现：

## 16.1 Hard Filter

```text
node online
solver tag compatible
CPU enough
memory enough
workspace reachable
node not drained
```

## 16.2 Score

使用简单可解释打分，例如：

```text
score =
  priorityWeight
+ waitingTimeWeight
+ freeCpuRatioWeight
+ freeMemoryRatioWeight
- runningJobsPenalty
```

代码中将权重放配置文件，不要硬编码魔法数字。

## 16.3 Reservation

调度前必须 Reserve：

```text
cpu_reserved += request_cpu
memory_reserved += request_memory
```

否则两个调度线程会同时看到相同空闲资源造成超卖。

公开复现可以：

```text
Redis Lua 做原子 reserve/release
+ MySQL ResourceReservation 记录审计
+ Reconciliation 校准 Agent 实际状态
```

或使用数据库 CAS 实现，只要保证原子性并有测试。

必须写并发测试：

```text
50 个线程同时申请一个只够 4 个 Job 的节点
```

验收：

```text
不得超卖 CPU/Memory
```

---

# 17. Compute Agent API

Python FastAPI Agent 至少实现：

```http
POST /worker/v1/jobs/submit
GET  /worker/v1/jobs/{externalExecutionId}
POST /worker/v1/jobs/{externalExecutionId}/cancel
GET  /worker/v1/node/status
```

Submit：

```json
{
  "executionRequestId": "exec-req-uuid",
  "jobNo": "CAE-20250618-0013",
  "workspace": "/workspace/project/case01",
  "solverProfile": "STRUCTURE_STANDARD",
  "resource": {
    "cpu": 8,
    "memoryGb": 16,
    "walltimeMin": 30
  }
}
```

返回：

```json
{
  "externalExecutionId": "node-a-000012",
  "accepted": true,
  "state": "SUBMITTED"
}
```

Agent 不接受用户任意 Shell 命令。

只允许白名单：

```text
solverProfile → known command template
```

防止平台演变成远程 RCE 工具。

---

# 18. Mock Solver

实现 Python Mock Solver，支持可配置：

```text
analysisType
runtimeSeconds
successProbability
failureMode
progressInterval
```

状态：

```text
QUEUED
RUNNING
SUCCESS
RUNTIME_FAILED
TIMEOUT
CANCELLED
```

运行时写：

```text
solver.log
progress.jsonl
raw_result.json
```

结构/模态成功结果至少生成：

```json
{
  "mass_kg": 312.6,
  "torsional_stiffness_nm_per_deg": 21540.2,
  "bending_stiffness_n_per_mm": 18450.1,
  "modes_hz": [28.6, 31.9, 42.3, 49.8]
}
```

NVH：

```text
peak_frequency_hz
peak_response_db
```

Crash：

```text
max_intrusion_mm
peak_acceleration_g
energy_balance_ratio
```

CFD：

```text
cd
cl
residual_final
converged
```

结果不要固定常数。根据 Case 参数和 deterministic random seed 生成可复现值。

---

# 19. Case Package 与 `.fem / INCLUDE`

必须做一套简单、真实感强的 Case Package。

示例：

```text
case-001/
├─ model.fem
├─ materials.inc
├─ properties.inc
├─ loads.inc
├─ manifest.json
└─ metadata.json
```

`model.fem` 可以是 Synthetic Text，不需要真实商业 Solver 格式完整语法，但至少模拟：

```text
INCLUDE 'materials.inc'
INCLUDE 'properties.inc'
INCLUDE 'loads.inc'
```

`manifest.json`：

```json
{
  "caseId": "STRUCT-TORSION-001",
  "entryDeck": "model.fem",
  "includes": [
    "materials.inc",
    "properties.inc",
    "loads.inc"
  ],
  "analysisType": "STRUCTURE",
  "conditionCode": "BIW_TORSION",
  "modelRevision": "V37",
  "parameters": {
    "thicknessScale": 1.0
  }
}
```

Validation 必须检查：

```text
entry deck exists
all include files exist
no absolute user desktop path
path traversal forbidden
manifest matches files
file checksum
file not modified after immutable snapshot
```

准备一个故障 Demo：

```text
model_bad_include.fem
→ INCLUDE missing_material.inc
→ VALIDATION_FAILED
→ Failure Code = INPUT_INCLUDE_MISSING
```

不要假装公开版支持真正 `.hm` 二进制解析。

README 中解释：

```text
.hm = source modeling asset
.fem/deck = solver execution input
```

---

# 20. Input Snapshot

一旦 Job READY/Submitted：

```text
输入 Case 不能被原路径修改后悄悄影响执行结果。
```

实现：

```text
source case
→ validate
→ copy/hardlink into immutable job workspace
→ calculate SHA-256
→ persist input_checksum
```

如果复现环境允许，可用文件复制完成。

必须展示：

```text
原始 demo-data 后续修改
不会改变已提交 Job 的 snapshot
```

---

# 21. Monitor / Reconciliation

必须实现后台定时任务。

作用：

```text
修复事件丢失
检查 RUNNING 太久
检查 SUBMITTED 但 Agent 无对应 execution
检查 UNKNOWN Job
补齐 Agent 状态
释放泄漏的 Reservation
```

原则：

```text
Event-driven for fast path
Reconciliation for correctness
```

不要只靠 MQ callback。

至少每 10 秒扫描 Demo 的活跃 Job。

多实例 Scheduled Task 使用：

```text
DB claim
或 Redisson short lock
```

并在文档中说明锁只是避免重复工作，不是业务最终正确性来源。

---

# 22. Cancel

实现取消竞态。

可能出现：

```text
用户 Cancel
同时 Agent 已启动 Solver
```

状态：

```text
RUNNING → CANCEL_REQUESTED → CANCELLED
```

如果远端取消失败：

```text
保留 CANCEL_REQUESTED / CANCEL_FAILED 信息
后续 Reconciliation 重试
```

绝不能前端一点击 Cancel 就直接把数据库改为 CANCELLED。

测试：

```text
submit 与 cancel 并发
running 时 cancel
已 SUCCESS 再 cancel
cancel 请求重复发送
```

---

# 23. Result Parser

实现 Strategy/Plugin：

```java
public interface ResultParser {
    boolean supports(AnalysisType analysisType, String solverType);
    ParseResult parse(ParseContext context);
    String version();
}
```

结构：

```text
StructureResultParser
NvhResultParser
CrashResultParser
CfdResultParser
```

Parser 幂等键：

```text
(job_id, parser_version)
```

重复消息不得产生重复 Metric。

允许用户主动：

```text
Reparse with parser v2
```

从而保留两个版本解析记录。

---

# 24. Post Processor

公开版不调用 HyperView。

实现 `MockHyperViewPostProcessor`：

```text
raw_result.json
→ matplotlib
→ contour-like PNG
→ mode-shape-like PNG
→ comparison bar chart
→ curve PNG
```

但接口设计必须与真实专业工具解耦：

```python
class PostProcessor(Protocol):
    def process(self, context) -> PostProcessResult: ...
```

PostProcessSpec 至少包括：

```text
condition_code
result_type
component
subcase
frame
view
selection_set
output_name
```

通过这一设计复现“报告数字正确但截图 Subcase 错”的现实问题。

必须有测试确保：

```text
Report KPI condition
== Image/PostProcess condition
```

---

# 25. 自动报告

结构/模态必须完整实现。

Pipeline：

```text
Raw Result
→ Result Parser
→ Structured Metrics
→ Post Processor
→ PNG/Curve
→ Report Context
→ Report Generator
→ PPTX Draft
```

使用：

```text
python-pptx
```

生成至少 4 页 PPTX：

```text
1. 项目信息 / 模型版本 / 工况
2. KPI 汇总表
3. 基准方案 vs 当前方案对比
4. 结果图 / 结论草稿占位
```

报告只生成：

> Draft / 初版报告

不能自动标记为最终工程签核。

Report 状态：

```text
DRAFT
REVIEWED
APPROVED（Demo 可仅 Admin 手动标记）
```

不同分析类型通过 ReportTemplate/ReportSpec 控制。

结构/模态完整；NVH/Crash/CFD 可只生成简化摘要页或 HTML/JSON Artifact。

---

# 26. Baseline Comparison

实现同工况历史基准对比。

例如：

```text
Baseline Job = BIW V36 Torsion
Current Job  = BIW V37 Torsion
```

后端计算：

```text
delta = current - baseline
delta_percent = delta / baseline * 100
```

禁止让 LLM 或报告层自己算数。

示例：

```json
{
  "metric": "TORSIONAL_STIFFNESS",
  "baseline": 20850.0,
  "current": 21540.2,
  "delta": 690.2,
  "deltaPercent": 3.31,
  "unit": "N*m/deg"
}
```

---

# 27. Failure Model

平台失败码必须和 Mock Solver 原始错误分离。

至少支持：

```text
INPUT_FILE_MISSING
INPUT_INCLUDE_MISSING
INPUT_INVALID
NO_RESOURCE
NODE_OFFLINE
AGENT_TIMEOUT
SUBMIT_REJECTED
SOLVER_RUNTIME_FAILED
SOLVER_TIMEOUT
POST_PROCESS_FAILED
REPORT_GENERATION_FAILED
CANCEL_FAILED
UNKNOWN_REMOTE_STATE
```

API 返回：

```json
{
  "code": "INPUT_INCLUDE_MISSING",
  "stage": "VALIDATION",
  "message": "Required include file is missing",
  "retryable": false,
  "details": {
    "file": "material.inc"
  }
}
```

保留：

```text
raw_solver_code
raw_message
```

但不把它们直接当 API Error Code。

---

# 28. 权限与安全

Demo 用户角色：

```text
CAE_ENGINEER
PROJECT_LEAD
ADMIN
```

权限示例：

```text
CAE_ENGINEER:
创建/查询自己的或有权限项目的 Job
取消自己的 Job
生成 Draft Report

PROJECT_LEAD:
查看项目全部 Job
调整普通优先级
选择 Baseline
Review Report

ADMIN:
管理 Compute Node
Resource Profile
Report Template
用户/角色
```

Agent API 使用固定 service token 或 HMAC 简化认证。

禁止：

```text
任意 shell command
任意 NFS absolute path
../ path traversal
上传后直接执行用户脚本
```

---

# 29. 前端

必须能实际演示，而不是只有 Swagger。

页面至少包括：

## Dashboard

显示：

```text
Running Jobs
Waiting Jobs
Failed Jobs
Compute Node Load
Success Rate
Average Queue Time
```

## Job List

支持筛选：

```text
project
analysis type
condition
status
submitter
date range
```

## Create Job

可以选择：

```text
Project
Model Revision
Analysis Type
Condition
Demo Case Package
Resource Profile
Priority
```

## Job Detail

展示：

```text
Current Status
Stage
Timeline
Execution Attempt
Target Node
Resource
Logs
Artifacts
Metrics
Report
```

通过 SSE 实时刷新状态。

## Compute Nodes

显示 CPU/Memory used/reserved 及 solver tags。

## Report

可以：

```text
Generate
Download PPTX
View Metrics
Choose Baseline
```

---

# 30. 可观测性

Spring Boot 暴露 Micrometer/Prometheus。

至少增加：

```text
cae_jobs_total{status,analysis_type}
cae_jobs_running
cae_jobs_waiting_resource
cae_job_submit_latency_seconds
cae_job_queue_seconds
cae_outbox_pending
cae_mq_consume_failure_total
cae_agent_offline_total
cae_resource_reservation_total
cae_reports_generated_total
```

Prometheus + Grafana Compose 启动。

提供基础 Dashboard JSON 或 provisioning。

不要花过多时间做漂亮 Grafana，重点是可运行。

---

# 31. 测试要求

这是强制项。

## Java Unit Test

```text
State Transition
Scheduler Filter/Score
Failure Mapping
Baseline Calculation
Report Spec Select
```

## Java Integration Test

优先 Testcontainers：

```text
MySQL
Redis
RabbitMQ
```

至少覆盖：

```text
Outbox transaction
Outbox publisher
Duplicate message
Job submit idempotency
Concurrent resource reservation
```

## Python pytest

```text
Agent submit/query/cancel
Mock Solver success/failure/timeout
Parser
PostProcessor
Report generation
```

## E2E

必须提供脚本自动验证完整链路。

---

# 32. 必须通过的 12 个 E2E 场景

## 场景 1：结构任务正常成功

```text
Create
→ Validate
→ Queue
→ Reserve
→ Submit
→ Running
→ Solver Success
→ Parse
→ Post
→ Report
→ SUCCESS
```

验收：

```text
Metrics 存 DB
Artifact 可下载
PPTX 存在
Job Event 完整
```

## 场景 2：INCLUDE 缺失

```text
VALIDATION_FAILED
```

Solver 不得启动。

## 场景 3：重复点击 Submit

发送相同 idempotency key 2~5 次。

验收：

```text
只有一个 JobExecution
只有一个 Mock Solver
```

## 场景 4：Agent 已接收但 Java 超时

故障注入：

```text
Agent 启动 execution
但第一次 response 延迟到客户端超时
```

MQ 重投。

验收：

```text
Agent 幂等返回旧 externalExecutionId
没有第二个 Solver
```

## 场景 5：Solver Runtime Failed

最终：

```text
RUNTIME_FAILED
```

保存 log 和 failure reason。

## 场景 6：无资源

所有节点被占满。

Job：

```text
WAITING_RESOURCE
```

释放资源后自动调度。

## 场景 7：Node Offline

运行中停止 Agent 心跳。

Job：

```text
UNKNOWN
```

恢复 Agent 后 Reconciliation 收敛。

不得立即重复跑 Solver。

## 场景 8：Cancel Race

Submit/Running 期间 Cancel。

最终状态正确且资源释放。

## 场景 9：Parser 重复消息

同一 `(job_id, parser_version)` 处理两次。

Metric 不重复。

## 场景 10：Batch 任务

创建 20~50 个 Child Job。

验证：

```text
Backpressure
不同节点分配
Parent Progress
部分失败不影响全部
```

## 场景 11：报告条件一致性

KPI 为 TORSION 时，Post Artifact 也必须对应 TORSION。

防止“数值是 A 工况，截图是 B 工况”。

## 场景 12：Backend 重启

在 Mock Solver RUNNING 时重启 Spring Backend。

验收：

```text
远端 Solver 继续运行
Backend 恢复后 Reconciliation 找回状态
```

---

# 33. Batch 与 Backpressure

Parent：

```text
cae_batch
```

Child：

```text
cae_job.batch_id
```

不要一次创建 10000 条然后瞬时发 10000 MQ。

Demo 可以：

```text
DB 分批 insert
Outbox 分批 publish
RabbitMQ prefetch 5~20
Scheduler 并发上限
per-discipline concurrency limit
```

前端显示：

```text
total
waiting
running
success
failed
cancelled
```

---

# 34. 数据索引

至少设计：

```text
UNIQUE(job_no)
INDEX(project_id, created_at, id)
INDEX(status, created_at)
INDEX(analysis_type, condition_code, created_at)
INDEX(submitter_id, created_at)
INDEX(current_execution_id)
```

Outbox：

```text
UNIQUE(event_id)
INDEX(status, next_retry_time)
```

Artifact：

```text
INDEX(job_id, artifact_type)
```

Result Metric：

```text
UNIQUE(job_id, parser_version, metric_code)
```

分页早期可以 Offset Pagination；文档说明百万 Job 时考虑 Keyset，不必过早实现。

---

# 35. 文件与下载

大文件不能：

```text
Browser → Spring JVM → byte[] → NFS
```

公开 Demo 小文件允许 HTTP 上传，但代码结构必须避免一次性读入内存。

优先：

```text
streaming upload
shared volume registration
artifact metadata registration
```

下载使用 Streaming Response 或 Nginx/X-Accel-Redirect（若实现方便）。

必须限制路径在项目 workspace root 内。

---

# 36. Demo 数据

至少准备：

```text
structure case good
structure case missing include
structure case baseline
structure case current
nvh case
crash case
cfd case
```

每个 Case 有不同 deterministic seed 和参数，使结果可重复。

---

# 37. Docker Compose

必须做到一条命令启动：

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

Compose 至少：

```text
mysql
redis
rabbitmq
backend
worker-node-a
worker-node-b
worker-node-c
frontend
nginx
prometheus
grafana
```

MinIO 可选：

```text
若实现后不会显著拖慢交付，则加入。
否则 derived artifact 先落 shared volume，并在 docs/future.md 说明对象存储扩展。
```

容器必须提供 healthcheck。

Backend 应等待依赖就绪，不要单纯 sleep 30。

---

# 38. Seed 初始化

项目启动自动创建：

```text
admin/admin123（README 明确仅 Demo）
engineer/engineer123
lead/lead123
```

Seed：

```text
2 projects
3 model revisions
4 analysis types
resource profiles
report templates
```

第一次打开 UI 就能操作。

---

# 39. 开发分阶段执行

不要一次生成所有代码。

按以下里程碑顺序实现，每个阶段都必须先构建、测试、提交一个稳定状态，再进入下一阶段。

## Milestone 0：Repository Bootstrap

完成：

```text
repo layout
Spring Boot starts
FastAPI Agent starts
Vue starts
Docker Compose dependencies
README skeleton
```

验收：

```text
backend /actuator/health = UP
agent /health = OK
frontend loads
```

## Milestone 1：Job Domain + MySQL

完成：

```text
Project/Model/Job tables
State Machine
Job CRUD
Job Event
Unit Tests
```

## Milestone 2：Case Validation + Snapshot

完成：

```text
manifest
include validation
checksum
immutable workspace
failure model
```

## Milestone 3：Compute Agent + Mock Solver

完成：

```text
submit/query/cancel
idempotent executionRequestId
mock solver
heartbeat
```

## Milestone 4：Scheduler + Reservation

完成：

```text
node registry
hard filter
score
reservation
waiting resource
concurrency test
```

## Milestone 5：RabbitMQ + Outbox

完成：

```text
outbox
publisher confirm
manual ack
submit queue
DLQ
idempotency
```

## Milestone 6：Monitoring + Reconciliation

完成：

```text
remote poll/callback
UNKNOWN
node lost
backend restart recovery
```

## Milestone 7：Result Parser

完成：

```text
structure full
NVH/crash/CFD pilot
metric storage
parser version
```

## Milestone 8：Post + Report

完成：

```text
mock contour/curve
baseline comparison
PPTX report
report version
```

## Milestone 9：Frontend

完成：

```text
dashboard
create job
job list
job detail
compute node
report
SSE
```

## Milestone 10：Batch + Failure Injection

完成：

```text
batch parent/child
backpressure
12 E2E scenarios
failure-demo.sh
```

## Milestone 11：Observability + Docs

完成：

```text
metrics
grafana
architecture docs
interview demo
benchmark
```

---

# 40. 每个里程碑的工作规则

每个 Milestone 完成后必须：

1. 运行 format/lint；
2. 运行后端单测；
3. 运行 Python 测试；
4. 运行当前已有集成测试；
5. 执行 Docker build；
6. 更新 `docs/progress.md`；
7. 更新 README 中已经真正可用的能力；
8. 不允许 README 描述尚未实现的功能为“已实现”。

如果发生技术方案变化，记录到：

```text
docs/adr/ADR-xxxx-*.md
```

至少建议形成：

```text
ADR-001 modular-monolith.md
ADR-002 rabbitmq-over-kafka.md
ADR-003 direct-agent-execution.md
ADR-004 outbox.md
ADR-005 shared-fs-for-solver-data.md
```

---

# 41. 编码风格

## Java

原则：

```text
Controller 不写业务
Application Service 编排
Domain 负责状态规则
Repository 隔离 persistence
Infrastructure 隔离 MQ/Redis/Agent
```

避免：

```text
God Service
500 行 JobService
Mapper everywhere
static util everywhere
```

核心类建议：

```text
CaeJob
JobStatus
JobTransitionPolicy
JobApplicationService
JobSubmissionService
JobCancellationService
JobReconciliationService
SchedulerService
NodeSelectionPolicy
ResourceReservationService
ComputeAgentClient
SolverProfileRegistry
OutboxEventPublisher
JobSubmitConsumer
ResultParseService
ReportApplicationService
```

## Python

避免把所有逻辑写在一个 `main.py`。

至少拆：

```text
agent_api.py
execution_manager.py
process_registry.py
mock_solver.py
parsers.py
postprocessors.py
report_generator.py
```

---

# 42. 必须展示的代码细节

README/Docs 中给出并真正对应代码：

## 42.1 状态 CAS

展示 SQL/Mapper。

## 42.2 Outbox Transaction

展示同一事务内：

```text
Job update + JobEvent + Outbox
```

## 42.3 Agent Idempotency

展示：

```text
executionRequestId → externalExecutionId
```

## 42.4 Resource Reservation

展示 Lua/DB CAS。

## 42.5 Reconciliation

展示为什么 Backend 重启不丢 Solver。

## 42.6 Parser Version

展示重复解析如何幂等。

## 42.7 Report Contract

展示：

```text
condition + subcase + metric + image
```

绑定关系。

---

# 43. 面试价值要求

最终 README 不要只是安装指南。

必须新增：

```text
## Why this project exists
## Architecture decisions
## Long-running job reliability
## Idempotency
## State machine
## Outbox
## Resource scheduling
## Failure recovery
## CAE file handling
## Result/report pipeline
## What is mocked and why
## What would change with real OptiStruct/HyperView
## Performance benchmark
## Interview demo
```

并用一张 Mermaid/PlantUML 架构图。

还要有一张 Sequence Diagram：

```text
User
→ Backend
→ DB/Outbox
→ RabbitMQ
→ Scheduler
→ Agent
→ Mock Solver
→ Reconciliation
→ Parser
→ Report
→ User
```

---

# 44. 面试演示脚本

在 `docs/interview-demo.md` 写一个 10~15 分钟演示。

建议：

## 第 1 分钟：业务

解释：

```text
公司有 Solver 和服务器，但流程碎片化。
```

## 第 2~4 分钟：正常任务

创建结构 Job，展示：

```text
VALIDATING
WAITING_RESOURCE
RUNNING
POST_PROCESSING
SUCCESS
```

## 第 5~7 分钟：核心后端

展示：

```text
State Machine
Outbox
RabbitMQ
Agent idempotency
```

## 第 8~9 分钟：失败恢复

触发重复 Submit 或 Agent 超时，证明只跑一个 Solver。

## 第 10~11 分钟：自动报告

展示：

```text
metrics
PNG
PPTX
baseline comparison
```

## 第 12~15 分钟：架构取舍

说明：

```text
为什么模块化单体
为什么 RabbitMQ
为什么 NFS
为什么 Redis 不是 Source of Truth
为什么不直接造 HPC Scheduler
```

---

# 45. Benchmark

公开版 Benchmark 是 `[LOCAL-BENCH]`，不得伪装成公司生产数据。

实现 `scripts/benchmark.sh`。

至少测试：

```text
1000 次 Job 查询 API
100 并发 Job 创建
50 并发资源申请
100 Job Batch submit
Outbox 1000 event publish
SSE 50 connections
```

报告：

```text
p50
p95
p99
error rate
DB CPU/connection observation
```

重点不是追高 QPS，而是证明：

```text
状态正确
幂等正确
资源不超卖
失败可恢复
```

---

# 46. 不要伪造企业 KPI

README 中企业故事与公开复现必须分开。

允许写：

```text
原业务场景目标：减少任务提交、结果整理和报告重复劳动。
```

不要写：

```text
本 GitHub 仓库证明公司当时提升 79.1%
本 GitHub 仓库真实处理 1428 个公司任务
```

公开项目指标只使用自己的实际测试数据。

---

# 47. Mock 与真实系统的 Adapter Mapping

必须在 `docs/real-system-mapping.md` 写：

| 公开复现 | 企业真实环境可能对应 |
|---|---|
| MockSolver | OptiStruct / Crash Solver / CFD Solver |
| DirectAgentAdapter | 内部服务器 Agent/SSH Wrapper |
| MockHyperViewPostProcessor | HyperView Python/Tcl Automation |
| Docker Volume | NFS / GPFS / NAS |
| Mock License Admission | 企业 License Server 查询 |
| Mock Node Pool | CAE CPU Compute Server Pool |

重点写清：

> 公开仓库验证的是平台架构与后端工程能力，不宣称复现商业 Solver 本身。

---

# 48. License Admission

公开版实现 Mock License Manager。

例如：

```text
OPTISTRUCT = 4 tokens
CRASH      = 2 tokens
CFD        = 2 tokens
```

任务需要：

```text
licenseFeature + tokenCount
```

Admission：

```text
有 Token → reserve
没有 → WAITING_RESOURCE / WAITING_LICENSE
```

但必须说明：

```text
Redis Counter 不是企业 License Server 的事实来源。
```

Mock License Manager 自己是公开 Demo 的唯一事实来源即可。

---

# 49. Solver Profile

不要允许用户输入启动脚本。

定义：

```yaml
solverProfiles:
  STRUCTURE_STANDARD:
    solver: MOCK_OPTISTRUCT
    analysisType: STRUCTURE
    defaultCpu: 8
    defaultMemoryGb: 16
    timeoutMin: 30
    allowedNodeTags: [STRUCTURE]

  CRASH_STANDARD:
    solver: MOCK_CRASH
    analysisType: CRASH
    defaultCpu: 16
    defaultMemoryGb: 32
    timeoutMin: 60
    allowedNodeTags: [CRASH]
```

Worker 只根据 Profile 执行预定义命令。

---

# 50. Resource Profile

业务用户可以选择：

```text
SMALL
STANDARD
LARGE
```

背后映射：

```text
CPU
Memory
Timeout
License
Node Tags
```

避免让普通工程师随意填 512 CPU。

Admin 可配置 Profile。

---

# 51. Job Number

实现易读 Job No：

```text
CAE-YYYYMMDD-XXXXXX
```

数据库主键仍用 BIGINT/UUID。

JobNo 只是业务标识。

并发生成必须不重复。

可采用：

```text
DB sequence table / Snowflake numeric id → format
```

不要用 `select max + 1`。

---

# 52. 日志

Backend 日志必须包含：

```text
traceId
jobNo
executionId
userId
```

Agent 日志包含：

```text
jobNo
externalExecutionId
nodeId
pid/processIdentity
```

前端 Job Detail 可以查看经过清洗的 Solver log tail。

不得直接暴露宿主机任意日志路径。

---

# 53. Audit

至少记录：

```text
JOB_CREATE
JOB_SUBMIT
JOB_CANCEL
JOB_RETRY
PRIORITY_CHANGE
REPORT_GENERATE
REPORT_REVIEW
NODE_DRAIN
```

字段：

```text
actor
operation
targetType
targetId
beforeJson
afterJson
createdAt
```

Demo 不要求做复杂 SIEM。

---

# 54. 前后端错误协议

统一：

```json
{
  "success": false,
  "code": "JOB_STATE_CONFLICT",
  "message": "Job cannot be submitted from RUNNING state",
  "traceId": "...",
  "details": {}
}
```

HTTP Status 正确使用：

```text
400 validation
401 unauthenticated
403 forbidden
404 not found
409 state/idempotency conflict
422 domain input invalid（可选）
500 unexpected
503 dependency unavailable
```

---

# 55. 最终验收标准

只有全部满足下面条件才算“项目完成”。

## 一键启动

```bash
docker compose up -d --build
```

新机器按照 README 能启动。

## UI 可演示

能创建、查询、取消、批量、下载报告。

## Reference Path 完整

结构/模态：

```text
Create
→ Validate
→ Schedule
→ Agent
→ Mock Solver
→ Parse
→ Post
→ PPTX
```

完整跑通。

## 可靠性

至少：

```text
Outbox
Idempotency
CAS State Machine
Resource Reservation
Reconciliation
DLQ
```

是真代码，不是文档概念。

## 故障演示

12 个 E2E 场景可通过脚本或测试复现。

## 测试

所有测试一次通过。

## 文档

架构、状态机、数据模型、失败恢复和面试 Demo 完整。

## 公开边界

没有公司数据，没有商业 Solver 依赖，没有虚假生产 KPI。

---

# 56. 最终交付时你必须输出的内容

完成后给出：

1. 最终目录树；
2. 架构摘要；
3. 技术选型；
4. 数据库核心表；
5. State Machine；
6. 正常 E2E 流程；
7. 12 个故障/验收场景及结果；
8. 测试统计；
9. Benchmark 真实结果；
10. Docker Compose 启动步骤；
11. Demo 用户；
12. Demo Case；
13. Report 下载位置；
14. 已实现能力；
15. 未实现 / Mock 能力；
16. 与真实 Altair/企业服务器对接需要替换哪些 Adapter；
17. 面试时最值得展示的 5 段代码路径。

最后运行一次完整：

```text
clean clone mental check
→ build
→ compose up
→ seed
→ demo
→ tests
```

发现失败必须修复后再宣告完成。

---

# 57. 工作过程中不要做的事情

禁止：

```text
1. 一次生成大量空壳类然后声称完成；
2. TODO 代替核心逻辑；
3. RabbitMQ Consumer sleep 等 Solver 完成；
4. 用 Redis 作为最终 Job 状态；
5. 只写 Happy Path；
6. 只做 Swagger 没 UI；
7. 只做 UI 没可靠性逻辑；
8. 把异常都 catch(Exception) 后标 FAILED；
9. 用任意 shell command 模拟 Solver Profile；
10. 把商业 Altair 软件作为测试必需环境；
11. 编造 benchmark 数字；
12. 编造公司生产数据；
13. 为了“微服务”把一个模块化单体拆碎；
14. 在 README 写已经实现但代码不存在的功能；
15. 省略数据库 migration/schema。
```

---

# 58. 优先级

如果上下文或时间不足，优先级严格按：

```text
P0：Job Domain + State Machine
P0：Case Validation + Snapshot
P0：Agent + Mock Solver
P0：Scheduler + Resource Reservation
P0：RabbitMQ + Outbox + Idempotency
P0：Reconciliation
P0：结构 Result Parser
P0：结构 Auto Report
P0：Docker Compose
P0：核心测试

P1：Frontend 完整体验
P1：Batch
P1：SSE
P1：Prometheus/Grafana
P1：Crash/CFD/NVH Pilot

P2：MinIO
P2：高级 UI
P2：更多指标/图表
P2：GitHub Actions
```

不得牺牲 P0 去做 P2。

---

# 59. 开始执行

现在开始从空仓库实现。

第一步：

```text
1. 输出 10~20 行实施计划；
2. 创建目录结构；
3. 初始化 backend/worker/frontend；
4. 创建 docker-compose 的 MySQL/Redis/RabbitMQ；
5. 让三个子项目都能启动；
6. 创建 docs/progress.md；
7. 完成 Milestone 0 的测试；
8. 然后不等待确认，继续 Milestone 1。
```

整个过程中持续维护：

```text
docs/progress.md
```

内容格式：

```markdown
## Milestone X
- [x] 已完成
- [ ] 未完成
- Tests: ...
- Known issues: ...
- Decisions: ...
```

只在真正需要用户提供外部凭证、商业许可证或不可获得的企业资产时停止。

对于本公开复现，这些都不是必需项，因此原则上应当可以自行完成整个项目。

