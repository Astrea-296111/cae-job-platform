# CAE 仿真任务调度、结果管理与自动报告平台项目经历（统一口径 + 团队协作终版）

> 项目时间：**2024-12-09 ～ 2025-07-25**  
> 团队规模：**7 人内部提效小组**  
> 我的角色：**组长 + 核心 Java 后端主开发**  
> 项目定位：**公司既有 CAE 计算服务器之上的任务控制面、结果资产和标准报告平台**  
> 主线技术：**Java 17 / Spring Boot 3.3.6 / MySQL 8 / Redis 7 / RabbitMQ 3.13.x / Python Compute & Post Worker / NFS / Vue 3 / HyperMesh / OptiStruct / HyperView**  
> 项目成熟度：结构刚度/模态为完整 Reference Path；NVH、碰撞、CFD 按不同深度 Pilot 接入。

---

# 0. 证据边界：这份文档里的“精确数字”怎么使用

这份文档把所有容易漂移的数字统一成一套**工程复盘口径**，目的是让任务数、用户数、批量任务、失败率、报告数量和人工提效能够互相计算，而不是面试时每次说一组不同的数据。

必须区分：

```text
[PROJECT]          我能确认的业务背景、岗位职责和系统主线
[REFERENCE]        为项目复盘统一的一套精确工程统计口径
[REFERENCE-STORY]  为讲清团队协作、验收过程而拟定的合理故事模板
[WEB-VERIFIED]     官方文档能直接确认的软件能力/发布时间
[LOCAL-BENCH]      本地或公开复现的压测，不能说成公司线上数据
[FUTURE]           如果重做/下一版方案，不说成当时已经完成
```

**面试推荐说法：**

> “我复盘这段经历时统一用这一套工程统计口径，主要保证任务量、提交动作、批量任务和提效数据算得上。如果公司正式审计，需要回到当时日志；我不会把复盘口径说成财务或生产审计值。”

---

# 0.1 一句话项目定义

> 公司本身已有 CAE 计算服务器、共享存储和商业 Solver，我们 7 个人在这些既有基础设施之上做了一个科室级 **CAE Job Control Plane**：统一管理车型/模型/工况、任务排队、计算节点选择、Solver 启动、长任务状态、结果资产和标准报告，减少工程师重复的 SSH、脚本提交、查状态、找结果和填报告工作。

---

# 0.2 一句话个人贡献

> 我是项目组长，同时自己承担核心 Java 后端：负责 Job/Execution Domain、MySQL 数据模型、状态机、RabbitMQ + Outbox、幂等 Submit、Compute Node Heartbeat、资源预占和 Scheduler、Java→Python Agent 协议、Reconciliation，以及 Result/Report 的后端契约；专业 Solver/Parser/HyperView 脚本由对应成员主写，我负责接口和联调。

---

# 0.3 实现成熟度：7 人 8 个月为什么做得完

```text
结构刚度 / 模态  [CORE-IMPLEMENTED]
- Job 完整链路
- OptiStruct Case Package / INCLUDE 预检
- Node Scheduler / Agent
- KPI Parser
- HyperView 标准后处理
- PPTX 报告草稿

NVH             [PILOT/PARTIAL]
- Job / Resource / Status 完整接入
- 部分标准曲线与 KPI
- 复杂声振诊断仍由工程师完成

碰撞安全        [PILOT]
- Job / Resource / Status / Result Artifact
- 少量固定工况关键字段、时间历程
- 不宣称自动完成法规/假人伤害最终结论

CFD / 风阻       [PILOT]
- Job / Resource / Status
- Cd / Cl / 收敛摘要
- 固定视图/结果资产
- 复杂流场专题报告仍以人工为主
```

---

# 0.4 项目最终统一工程参考卡片 `[REFERENCE]`

## 时间

```text
Kickoff                  2024-12-09
流程盘点完成              2024-12-20
结构 MVP                 2025-01-17
Scheduler + Agent Beta   2025-02-28
Case Package / Precheck  2025-03-21
Batch + MQ + 多专业 Beta   2025-04-25
结构自动报告 Beta          2025-05-30
部门 Pilot Start         2025-06-02
Pilot Mid Review         2025-06-27
Final Acceptance         2025-07-18
文档/交接收尾              2025-07-25
```

## 团队和用户

```text
项目组                    = 7 人
部门 Pilot 授权用户         = 24 人
实际至少使用 1 次的用户      = 21 人
专业组                    = 4 组
```

Pilot 用户分布：

```text
结构/模态                 = 7
NVH                      = 5
碰撞                     = 6
CFD/风阻                  = 6
----------------------------
Authorized Total         = 24
```

## 计算资源池

本文把 Pilot 纳管的服务器池统一为 **8 台 / 704 Physical Cores / 4 TB RAM**：

| 节点组 | 数量 | 单节点 CPU | 单节点内存 | 小计 CPU | 小计内存 |
|---|---:|---:|---:|---:|---:|
| Standard | 4 | 64 Core | 256 GB | 256 | 1,024 GB |
| Memory | 2 | 96 Core | 512 GB | 192 | 1,024 GB |
| Large | 2 | 128 Core | 1,024 GB | 256 | 2,048 GB |
| **合计** | **8** | - | - | **704** | **4,096 GB** |

> 这表示“平台 Pilot 纳管范围”，不代表公司全部算力资产。

## Agent / Scheduler 固定参数

```text
Agent heartbeat interval       = 15 s
Node SUSPECT threshold         = 45 s
Node OFFLINE threshold         = 90 s
Running Job reconcile interval = 120 s
Submit timeout                 = 60 s
Idempotency key TTL            = 24 h
```

## 默认 Resource Profile `[REFERENCE]`

| 专业 | CPU | Memory | Walltime Limit | Pilot P50 Solver Runtime |
|---|---:|---:|---:|---:|
| 结构/模态 | 32 Core | 96 GB | 120 min | 41.2 min |
| NVH | 48 Core | 192 GB | 240 min | 96.4 min |
| 碰撞 | 96 Core | 384 GB | 480 min | 271.8 min |
| CFD/风阻 | 96 Core | 512 GB | 600 min | 223.5 min |

这些 Profile 是默认模板，不是说每个 Case 永远使用固定资源；专业 Owner 可以在允许区间内调整。

## Pilot 统计窗口

```text
Pre-platform baseline sample   = 2024-10-14 ～ 2024-11-29（35 个工作日）
Development                    = 2024-12-09 ～ 2025-05-30
Pilot                          = 2025-06-02 ～ 2025-07-18（35 个工作日）
Close-out                      = 2025-07-21 ～ 2025-07-25
```

## Pilot 提交与执行

```text
UI / API submit attempts       = 779
Validation blocked             = 47
Accepted submission actions    = 732
  ├─ single-job submissions    = 708
  └─ batch submissions         = 24

Batch child jobs               = 720
Single jobs                    = 708
-----------------------------------
Execution Jobs                 = 1,428
```

因此：

> `1,428` 是 Execution Job 数，不等于工程师点击了 1,428 次 Submit。

## 1,428 Job 专业分布

```text
结构/模态 = 551
NVH      = 307
碰撞     = 268
CFD/风阻  = 302
----------------
Total    = 1,428
```

Batch Child 的统一参考分布：

```text
结构/模态 = 360
NVH      =  90
碰撞     =  30
CFD/风阻  = 240
----------------
Batch    = 720
```

对应非 Batch Single Job：

```text
结构/模态 = 191
NVH      = 217
碰撞     = 238
CFD/风阻  =  62
----------------
Single   = 708
```

## Final Job Outcome

```text
SUCCESS              = 1,340  (93.84%)
CANCELLED            =    29  ( 2.03%)
RUNTIME_FAILED       =    24  ( 1.68%)
SUBMIT_FAILED        =    21  ( 1.47%)
POST_PROCESS_FAILED  =    14  ( 0.98%)
------------------------------------
TOTAL                = 1,428
```

说明：

- `RUNTIME_FAILED` 包括 Solver 不收敛、输入虽合法但运行期失败等；
- `SUBMIT_FAILED` 主要是计算节点环境、挂载、启动命令等问题；
- `POST_PROCESS_FAILED` 不代表 Solver 结果丢失，通常可以修 Parser 后 Re-process。

## 提交错误口径

Baseline：

```text
人工提交记录              = 345 次
可避免提交缺陷             = 30 次
Defect Rate              = 30 / 345 = 8.70%
```

30 次缺陷拆分：

```text
INCLUDE / 路径问题         = 12
资源/环境参数问题           =  8
重复/误提交                =  6
结果目录/登记不一致         =  4
```

Pilot：

```text
Total submit attempts     = 779
Validation blocked early  = 47  (6.03% of attempts)
Accepted actions          = 732
Preventable submit fail   = 21  (2.87% of accepted actions)
```

平台没有让工程师“永远不犯错”，而是把大量问题前移到 Validation；真正进入外部执行后的可避免提交失败率统一使用：

```text
8.70% → 2.87%
relative reduction = 67.0%
```

## 人工操作提效

### 单任务

配对抽样：40 个标准单任务操作。

```text
人工流程 median   = 12.4 min
平台流程 median   =  4.1 min
节省               =  8.3 min / operation
reduction          = 66.9%
```

### 50-Case Batch

抽样：8 个代表性 50-Case 批量任务。

```text
人工逐项准备/提交 median = 47.6 min
平台 Batch median       =  5.8 min
节省                     = 41.8 min / batch
reduction                = 87.8%
```

### 标准结构报告

Pilot 形成并人工审核的标准报告：**32 份**：

```text
扭转刚度 = 18
弯曲刚度 =  8
模态     =  6
----------------
Total    = 32
```

```text
传统人工整理 median = 86.4 min / report
平台草稿+人工审核    = 18.2 min / report
节省                  = 68.2 min / report
reduction             = 78.9%
```

### Pilot 总人工节省估算

严格按非重叠“人工动作”统计：

```text
708 single operations × 8.3 min = 5,876.4 min = 97.94 h
24 batch operations × 41.8 min   = 1,003.2 min = 16.72 h
32 reports × 68.2 min            = 2,182.4 min = 36.37 h
--------------------------------------------------------
Estimated total                 = 151.03 person-hours
```

对外统一说：

> **试运行阶段按提交动作和报告份数分开估算，累计减少约 151.0 人时的重复操作。**

这次不再用 `1,428 Job × 单任务节省`，避免 Batch Child Job 重复计算。

## 状态可靠性和补偿

Pilot 中统一记录：

```text
Auto reconciled stale jobs      = 31
  ├─ completion event missed    = 18
  ├─ Agent reconnect/network    =  9
  └─ external manual terminate =  4

Duplicate submit blocked        = 19
Confirmed duplicate Solver run
caused by MQ redelivery after fix = 0
```

## 内部反馈

Pilot Issue / Feedback 共 `63` 条：

```text
UI / 使用体验              = 21
Input / Path / Manifest    = 16
状态 / 执行                = 11
报告 / 模板                =  9
权限 / 导出                =  6
------------------------------
Total                      = 63
```

```text
Final acceptance 前关闭      = 56
转下一阶段 backlog           =  7
Closure rate               = 88.9%
```

---

# 1. 项目背景：为什么一个“已经有 Solver 和服务器”的科室还要做平台

公司原来并不缺：

```text
HyperMesh / 前处理工具
OptiStruct / 碰撞 / CFD 商业 Solver
Linux 计算服务器
共享文件系统
Solver License
```

缺的是：

> **工程师和这些基础设施之间的统一业务层。**

原流程大致是：

```text
建模 / 导出 Solver Deck
→ 找共享目录
→ 登录服务器
→ 看哪台机器空
→ 改脚本 / CPU / Memory
→ 启动 Solver
→ 记住 Job / PID / 目录
→ 过一段时间回来查 Log
→ 找 Result
→ HyperView / HyperGraph 后处理
→ 截图 / 抄 KPI
→ 填 PPT / Excel
```

单人、少量 Case 时，这套流程没有问题。

当几个车型、多专业和批量方案同时推进以后，问题集中暴露：

```text
1. 模型版本和结果目录靠个人命名；
2. 工程师偏向使用熟悉的服务器，资源不均；
3. 同一个 Case 可能因为网络/重复点击被多次启动；
4. 大量任务需要人工查状态；
5. INCLUDE / 共享路径问题在远端才暴露；
6. 不同小组都在重复做截图、曲线和报告模板；
7. 历史结果无法按车型/版本/工况快速检索。
```

因此项目出发点不是“为了写 Java”，而是：

> **把已经存在的算力和 Solver 变成科室可以复用的一套标准计算服务。**

---

# 2. 为什么由 CAE 科室做，而不是公司 IT 从零建设

项目组和 IT/CAE 算力管理员的职责明确分开。

## 公司 IT / CAE Compute Admin

```text
服务器硬件
Linux 基础环境
网络
NFS / 共享存储
Solver 安装
License Server
账号 / 权限基础设施
```

## 7 人项目组

```text
Project / Model / Condition / Job 业务模型
任务页面
Scheduler / Resource Admission
Compute Agent 接口
Solver Profile
Result / Artifact Metadata
HyperView 后处理流程
标准报告模板
内部试运行和反馈闭环
```

所以不是 7 个人“从机房布线开始搭 HPC”，而是在已有基础设施上做业务软件。

---

# 3. 团队组成：7 个人怎么分工

## 3.1 我：组长 + 核心 Java 后端

我负责：

```text
需求拆解和里程碑
Job / Execution Domain
MySQL Schema
状态机 / CAS
RabbitMQ / Outbox / Idempotency
Node Registry / Heartbeat
Resource Reservation / Scheduler
ComputeAgentClient / Contract
Reconciliation
Result / Artifact / Report Metadata
接口 Review / Code Review / 联调
```

## 3.2 成员 A：前端

```text
Vue 3
Job 创建页面
Batch 创建
任务列表 / Filter
SSE 实时状态
Log / Artifact / Report 页面
Dashboard
```

## 3.3 成员 B：Python Compute/Post Worker

```text
Compute Agent
受控 Solver 启动
Process / externalExecutionId 管理
Result Artifact 收集
HyperView Post Worker 框架
Python Parser Framework
```

## 3.4 成员 C：结构/模态 + NVH Domain Owner

```text
结构/模态 JobTemplate
OptiStruct Case 规范
INCLUDE / 输入规则
ResourceProfile
KPI Definition
结构报告视角 / Subcase / Result Type
NVH 基础结果字段
```

## 3.5 成员 D：碰撞 Domain Owner

```text
Crash JobTemplate
长任务资源模板
Solver Wrapper 需求
Result Directory Rule
关键曲线/时间历程字段
失败分类
```

## 3.6 成员 E：CFD / 风阻 Domain Owner

```text
CFD Case 目录规范
ResourceProfile
Cd / Cl / Convergence Definition
固定视图和收敛曲线需求
```

## 3.7 成员 F：测试 + 报告模板 + 部署联调

```text
Golden Case
回归测试
PPTX 模板维护
Docker / Nginx
Prometheus / Grafana 基础看板
Pilot Issue Triage
```

> 专业 Owner 不等于“每人独自写一个微服务”。他们主要负责 Solver 领域事实、样例 Case、结果定义和专业验收；平台公共能力由我和 B/A/F 一起实现。

---

# 4. 团队怎么真正合作：不是“每周开会”，而是 Data Contract

> `[REFERENCE-STORY]`：下面的接口颗粒度、例会节奏和里程碑用于形成完整且合理的团队协作故事。如果与你实际经历有冲突，以真实经历替换。

我们最后把协作边界定成七个对象：

```text
JobTemplate     = 某专业某工况“怎么跑”的规范
CasePackage     = 一次求解的输入资产
Job             = 用户眼里的业务任务
Execution       = Job 的一次真实执行尝试
Artifact        = 输入/日志/结果/截图等文件资产
Metric          = 可检索的结构化结果
ReportSpec      = 报告需要哪些指标、曲线和视图
```

## 4.1 为什么这个粒度很重要

一开始大家容易说：

> “给后端一个目录，你自己判断怎么跑。”

这会让 Java Backend 被迫理解每个 Solver 的全部细节。

后来约定：

> **专业 Owner 负责“领域事实”，我负责“生命周期和可靠性”。**

专业侧交给平台：

```yaml
discipline: STRUCTURE
condition_code: BIW_TORSION
solver_profile: OPTISTRUCT_STATIC
resource_profile: STRUCT_STD_32C_96G
entry_file_pattern: "*.fem"
required_artifacts:
  - OUT
  - H3D
metrics:
  - TORSIONAL_STIFFNESS
  - MASS
post_profile: BIW_TORSION_POST_V4
report_template: BIW_TORSION_REPORT_V4
```

平台保证：

```text
创建
校验
排队
资源预占
启动
状态
取消
重试
结果登记
报告版本
审计
```

---

# 5. 我和其他成员具体怎么“接数据、出数据”

## 5.1 C/D/E → 我：给的是 JobTemplate + Golden Case，不是口头描述

每个专业接入至少需要：

```text
1. condition_code
2. sample case directory
3. entry file
4. input validation rules
5. default resource profile
6. solver start/stop semantics
7. success / failure signature
8. expected artifacts
9. metric definition
10. report requirements
```

例如结构组给我的不是一句：

> “支持 OptiStruct 就行。”

而是：

```text
Case: BIW Torsion
Entry: main.fem
Precheck: INCLUDE complete + check-run
Required Result: .out + .h3d
Metric: torsional stiffness / mass
Post: torsion subcase + displacement contour
Report: baseline delta + iso/front image
```

## 5.2 我 → B：Execution Contract

我给 Python Agent 的不是任意 Shell：

```json
{
  "executionId": 990871,
  "idempotencyKey": "job-871233-attempt-1",
  "nodeId": "CAE-NODE-07",
  "workspace": "/cae/jobs/E260/CAE20250520-0087/run",
  "solverProfile": "OPTISTRUCT_STATIC",
  "entryFile": "main.fem",
  "cpu": 32,
  "memoryGb": 96
}
```

Agent 输出统一：

```json
{
  "externalExecutionId": "node07-exec-990871",
  "accepted": true,
  "pid": 183224,
  "startedAt": "2025-05-20T10:28:16"
}
```

B 不需要操作 MySQL Job State；Java 也不允许用户传任意 Shell Command。

## 5.3 我 → A：UI State Contract

前端不自己根据十个底层字段猜任务状态。

我统一返回：

```text
VALIDATING
WAITING_RESOURCE
DISPATCHING
RUNNING
POST_PROCESSING
REPORTING
SUCCESS
FAILED
CANCELLED
UNKNOWN
```

另外返回：

```text
stage
reason_code
retryable
progress_hint
```

这样 A 可以稳定做页面，而不用知道某个 Solver 的 Exit Code。

## 5.4 B / C → 我：Result Contract

Post Worker 最后输出统一 Envelope：

```json
{
  "jobId": 871233,
  "executionId": 990871,
  "parserVersion": "biw-torsion-v4",
  "metrics": [
    {"code":"TORSIONAL_STIFFNESS","value":22453.1,"unit":"Nm/deg"},
    {"code":"MASS","value":287.4,"unit":"kg"}
  ],
  "artifacts": [
    {"type":"CONTOUR_IMAGE","uri":".../deformation_iso.jpg"},
    {"type":"SOLVER_OUT","uri":".../main.out"}
  ]
}
```

Java 负责入库、版本和报告状态；Python/HyperView 负责专业结果读取。

---

# 6. 团队沟通节奏 `[REFERENCE-STORY]`

为了不让 7 个人所有问题都在一个群里讨论，我们按三层沟通。

## 6.1 每周一 09:30～10:00：7 人 Project Sync

只过：

```text
本周目标
上周未完成项
接口变化
阻塞问题
风险
```

不在会上讨论某个正则表达式或 SQL 细节。

## 6.2 周中：Interface Review，只有相关成员参加

例如：

```text
我 + B：Agent / idempotency / cancel
我 + C：JobTemplate / Result Contract
C + B + F：HyperView PostProfile
我 + A：状态/错误码/API
```

接口一旦变更要改版本或 Contract 文档，不靠“群里通知一下”。

## 6.3 每 2～3 周 Milestone Demo

参加人：

```text
7 人项目组
科室负责人/项目 Sponsor
对应专业组代表
必要时 CAE 算力管理员
```

Demo 不看 PPT 为主，而看真实 Case 能不能端到端跑。

---

# 7. 一个团队协作冲突故事：Resource Profile 到底谁说了算

`[REFERENCE-STORY]`

## 背景

第一版创建任务允许用户自由填：

```text
CPU
Memory
Walltime
Target Server
```

专业工程师觉得灵活，前端也容易做。

## 问题

试用后同一个结构工况出现：

```text
A：16 Core / 64 GB
B：32 Core / 128 GB
C：64 Core / 256 GB
```

而工程目的其实一样。

结果：

```text
资源浪费
调度不可预测
新同事不知道怎么填
```

## 讨论

专业 Owner 认为不同模型规模确实需要调整，我也不希望后端把资源写死。

最后拆成：

```text
专业 Owner：维护 Profile Default + Allowed Range
Backend：版本化 Profile + 校验 + Reservation
用户：选择 Profile，可在 Allowed Range 里微调
```

例如：

```text
STRUCT_STD_32C_96G
Default CPU   = 32
CPU Range     = 16 ~ 48
Default Mem   = 96 GB
Memory Range  = 64 ~ 160 GB
```

## 结果

这个改动后来直接成为 Scheduler 的输入契约。

**面试价值：**

> 不是“我和同事意见不合”，而是把专业自由度与平台标准化拆成责任边界。

---

# 8. 第二个协作故事：`.fem` 存在，为什么服务器还是跑不起来

`[REFERENCE-STORY]`

## 现象

结构组给了一个本机可以运行的 OptiStruct Case，但平台迁到 Node 后报 Include File Missing。

## 联调

C 负责确认模型语义；B 负责运行环境；我负责输入 Contract。

三方一起排查后发现：

```text
main.fem
  INCLUDE D:/user/project/material.fem
```

在工程师本地成立，在 Linux Node 上当然不存在。

## 最终责任拆分

```text
C：定义“一个完整结构 Case 必须包含什么”
B：Agent 负责 Workspace 和运行环境
我：平台增加 CaseManifest / dependency validation / snapshot
```

最终：

```text
OptiStruct -inventory
+ path allowlist
+ relative path normalize
+ optional -check
+ immutable input snapshot
```

这类错误从“跑起来以后才失败”，变成“提交前就告诉工程师缺什么”。

---

# 9. 第三个协作故事：报告数字是对的，截图却错了

`[REFERENCE-STORY]`

早期 PostProfile 只写：

```text
result_type = Displacement
view = iso
```

一个 H3D 有多个 Subcase 时，自动截图脚本默认取错了 Subcase。

结果：

> KPI 来自扭转工况，但 PPT 图片来自另一个工况。

这个问题程序不会 Crash，却是更危险的数据语义错误。

后来我和 C/F/B 把 Report Artifact Contract 改成：

```text
execution_id
subcase_id
simulation / frame
result_type
component
layer
selection_set
view
post_profile_version
```

生成报告前必须校验：

```text
Metric.execution_id == Image.execution_id
Metric.condition == PostProfile.condition
```

这能很好地说明：

> **跨模块协作真正难的是“语义契约”，不是接口能不能 HTTP 200。**

---

# 10. 项目时间线和领导验收 `[REFERENCE-STORY]`

## 2024-12-09：Kickoff

科室负责人提出的目标不是“开发一个 Java 系统”，而是：

> 把标准仿真流程中重复、可规则化的操作尽量平台化，让工程师把时间放到建模和工程判断。

### 第一阶段 Deliverable

```text
先选一个结构标准工况
必须真实跑 Solver
能看到状态
能找到结果
```

---

## 2024-12-09 ～ 12-20：业务盘点

我们访谈 `12` 名代表工程师：

```text
结构/模态 3
NVH      3
碰撞     3
CFD      3
```

同时整理 2024-10-14～11-29 的 `345` 次历史人工提交记录。

12-20 的输出不是代码，而是：

```text
Pain Point List
Job Lifecycle v0.1
4 组 Input/Output Matrix
Golden Case List
第一版数据字典
```

负责人验收关注：

> “你们是不是找到了真正重复的流程，还是只想做一个漂亮页面？”

---

## 2025-01-17：结构 MVP Demo

演示：

```text
HyperMesh 导出 .fem
→ Web 创建 Job
→ Agent 启动 OptiStruct
→ 页面状态 RUNNING
→ Solver 完成
→ Result URI 可查
```

### MVP 验收

```text
8 个结构 Golden Case
8 / 8 完成端到端
```

负责人给的反馈：

> “能跑只是第一步，下一个阶段要让工程师不用自己判断哪台服务器空。”

---

## 2025-02-28：Scheduler + Agent Beta

纳管 `8` 台 Pilot 节点。

验收用 `64` 个 Mock/轻量 Test Job 做并发调度：

```text
没有超过节点 CPU/Memory Reservation
Agent 掉线后节点能进入 SUSPECT/OFFLINE
Job 不会因为 Agent 短暂掉线立即重复启动
```

负责人主要看：

```text
服务器负载是否更透明
是否还能手工 Override
出了问题能不能找到 Job 在哪台机器
```

---

## 2025-03-21：Case Package / Precheck Review

重点不是加功能，而是解决“模型文件搬到服务器就坏”的问题。

测试集：`96` 个历史结构 Case。

统一参考：

```text
发现路径 / INCLUDE 类问题 = 11 个
Precheck 在真正 Solver 启动前发现 = 10 个
1 个属于运行环境挂载问题，进入 Submit Failed
```

从这一版开始，平台不再把“有一个 `.fem` 文件”视为“Case 完整”。

---

## 2025-04-25：Batch + MQ + 多专业 Beta

这时 RabbitMQ 才成为明确需求：

```text
Batch Job
Submit Command
Post Command
Retry/DLQ
```

验收重点：

```text
结构完整链路稳定
NVH / Crash / CFD 至少能统一创建、排队、跟踪和归档
50-Case Batch 不需要前端循环点 50 次
```

---

## 2025-05-30：结构自动报告 Beta

Demo：

```text
结构 Solver Success
→ Result Parser
→ HyperView 自动打开结果
→ 切 Subcase / Contour / View
→ Capture Image
→ KPI + Baseline Delta
→ PPTX Draft
```

当时负责人最关心的一句话是：

> “程序可以出草稿，但最终工程结论谁签？”

所以状态明确拆：

```text
REPORT_DRAFT
REPORT_REVIEWING
REPORT_APPROVED
```

不把程序草稿写成“自动签核”。

---

## 2025-06-02：部门 Pilot 开始

授权 `24` 人，实际使用 `21` 人。

结构组作为完整 Reference Path；其他三组按 Pilot 深度逐步开放。

---

## 2025-06-27：Pilot Mid Review

中期评审不再看“功能有没有”，而开始看：

```text
Job 数
失败原因
Validation 拦截
批量提交耗时
报告耗时
用户 Issue
```

领导要求后半段：

> “不要继续堆新功能，先把失败原因、状态恢复和报告可追踪做好。”

因此 6～7 月重点变成：

```text
Outbox
Idempotency
Reconciliation
Error Code
Template Version
Index / Search
```

---

## 2025-07-18：Final Acceptance

最终现场验收统一演示 `6` 个场景：

```text
1. 结构单 Job 完整链路
2. 50-Case Batch
3. 重复 Submit / Idempotency
4. Node/Agent 异常后的 Reconciliation
5. Solver Success 后 Re-process + Report Regenerate
6. 不同专业按 Project/Model/Condition 查历史结果
```

`6 / 6` 场景完成。

领导最终验收更关注：

```text
平台是否真的减少手工步骤
出了问题能否解释和恢复
结果/报告能否追溯版本
不同小组是否愿意把标准任务放进来
```

---

## 2025-07-25：交接收尾

输出：

```text
部署手册
JobTemplate 接入说明
Solver/Profile 配置说明
Agent 运维说明
常见失败码
数据库字典
ReportTemplate 版本规则
Backlog
```

---

# 11. 内部反馈怎么讲得真实

## 反馈 1：结构组——“不要让我们重新上传几十 GB 的 Case”

最初 Web Upload 思路被否掉。

改成：

```text
已有 NFS Case → Register Path + Manifest
Web 新文件 → Object Storage / Staging（可选）
```

Backend 管权限和 Metadata，大文件尽量不穿 JVM。

## 反馈 2：碰撞组——“Runtime Failed 不要自动重跑”

碰撞 Job 很贵。

所以 Retry 分类：

```text
Network / Agent temporary error → auto retry
Input / non-convergence / solver fatal → no blind retry
```

## 反馈 3：CFD 组——“最需要的是 Batch，不是更漂亮的 Dashboard”

导致 4 月优先做 Parent/Child Batch 和 Template Clone，而不是继续做 UI 动效。

## 反馈 4：科室负责人——“不要把 QPS 当项目成果”

最终业务 KPI 改成：

```text
单任务人工操作时间
Batch 操作时间
可避免失败率
标准报告整理时间
状态可追踪性
```

## 反馈 5：报告审核——“自动截图必须能证明来自哪个工况”

导致 Artifact 增加：

```text
execution_id
subcase_id
post_profile_version
report_template_version
```

---

# 12. 技术栈和 2024.12～2025.07 是否匹配 `[WEB-VERIFIED]`

## Java / Spring

统一版本：

```text
Java 17
Spring Boot 3.3.6
Spring MVC
Spring Security
Spring AMQP
MyBatis-Plus 3.5.x
```

Spring Boot 3.3 GA 在 **2024-05-23**，3.3.6 在 **2024-11-21** 发布，所以 2024-12 新项目采用 3.3.6 时间上自然。

## Data / Async

```text
MySQL 8
Redis 7 / Redisson
RabbitMQ 3.13.x
```

## Deployment

```text
Docker
Nginx
Prometheus / Grafana
Spring Scheduling
```

不把 Kubernetes、Kafka、Elasticsearch 硬塞成“已用”。

## Frontend

```text
Vue 3
SSE
```

## Compute / CAE

```text
Python Compute Agent
Python Post Worker
HyperMesh / Tcl / Python API（按安装版本）
OptiStruct CLI
HyperView / HyperGraph automation
python-pptx 或 HyperWorks Report API
NFS / Shared Engineering Storage
```

Altair 2024 官方资料可以直接支持：

```text
HyperMesh 2024 = 第一版 Python API，同时 Tcl 仍是成熟路径
OptiStruct CLI 支持 .fem 和 -check
OptiStruct -inventory 可输出运行所需输入文件列表
-incpath 可控制 INCLUDE 搜索路径
HyperView Python API 可加载结果、控制视图/Result、截图
CaptureImageTool 可导出图片
Report API 官方示例可生成 PPTX
```

所以“Solver → HyperView 自动后处理 → PPTX 草稿”的技术链与 2024/2025 时间匹配。

---

# 13. 系统总体架构

```text
CAE Engineer
    ↓
Vue 3 / Internal Nginx
    ↓
Spring Boot 3.3.6 Control Plane
    ├─ Project / Model / Condition
    ├─ Job / Execution State Machine
    ├─ Scheduler / Resource Admission
    ├─ Artifact / Result / Report
    └─ Auth / Audit
    ↓              ↓              ↓
 MySQL 8        Redis 7       RabbitMQ
    ↓              ↓              ↓
    └──────────────┼──────────────┘
                   ↓
       Python Compute/Post Worker
                   ↓
            ComputeAdapter SPI
             ├─ DirectAgentAdapter  ← 项目主线
             └─ BatchSchedulerAdapter [可选]
                   ↓
       Company CAE Compute Node Pool
                   ↓
      Solver → Raw Result / Log
                   ↓
    Parser / HyperView / HyperGraph
                   ↓
       KPI / Image / Curve / Report
                   ↓
                Backend
```

---

# 14. 项目的输入和输出

## 输入

```text
Project
Model Version
Discipline
Analysis Type
Condition / Load Case
Entry Deck / Case Package URI
Solver Profile
Resource Profile
Priority
Baseline Job（可选）
Post Profile（可选）
Report Template（可选）
```

API：

```json
POST /api/v1/jobs
{
  "projectId": 1024,
  "modelVersion": "BIW_V37",
  "discipline": "STRUCTURE",
  "analysisType": "STIFFNESS",
  "condition": "TORSION",
  "entryDeckUri": "nfs://cae/E260/BIW_V37/torsion/main.fem",
  "solverProfile": "OPTISTRUCT_STATIC",
  "resourceProfile": "STRUCT_STD_32C_96G",
  "priority": "NORMAL",
  "reportTemplate": "BIW_TORSION_V4"
}
```

同步输出：

```json
{
  "jobNo": "CAE20250520-0087",
  "status": "VALIDATING"
}
```

最终异步输出：

```text
Final Job Status
Execution Timeline
External Execution ID
Logs
Raw Result URI
Metrics
Images / Curves
Report Draft
Failure Code（失败时）
```

---

# 15. MySQL 核心模型

```text
cae_project
cae_model_version
cae_job
cae_job_execution
cae_job_event
cae_compute_node
cae_resource_profile
cae_resource_reservation
cae_solver_profile
cae_artifact
cae_result_metric
cae_report
cae_outbox_event
```

## 为什么 Job / Execution 分开

```text
Job = 用户眼中的一次业务任务
Execution = 一次真实运行 attempt
```

Retry 不覆盖历史：

```text
Job 871233
├─ Execution #1 FAILED
└─ Execution #2 SUCCESS
```

---

# 16. 状态机

```text
CREATED
→ VALIDATING
→ READY
→ WAITING_RESOURCE
→ QUEUED
→ DISPATCHING
→ RUNNING
→ POST_PROCESSING
→ REPORTING
→ SUCCESS
```

异常：

```text
VALIDATION_FAILED
SUBMIT_FAILED
RUNTIME_FAILED
POST_PROCESS_FAILED
REPORT_FAILED
CANCELLED
UNKNOWN
```

状态更新最终用 DB CAS：

```sql
UPDATE cae_job
SET status = ?, version = version + 1
WHERE id = ?
  AND status = ?
  AND version = ?;
```

`affected_rows = 0` 说明发生了并发、重复或乱序事件，不能直接覆盖。

---

# 17. Scheduler：不是自研 Slurm

硬过滤：

```text
Node ONLINE
Solver Capability Match
CPU Enough
Memory Enough
Storage Reachable
License Admission Pass
```

再评分：

```text
score =
  0.35 * cpu_free_ratio
+ 0.25 * memory_free_ratio
- 0.20 * running_job_ratio
+ 0.10 * wait_age_bonus
+ 0.10 * data_locality_bonus
```

这只是 `[REFERENCE]` 配置，不需要面试死背权重。

关键口径：

> 平台只管理科室 Pilot 服务器池的业务排队、资源预占和节点选择；不实现上千节点拓扑、MPI Fabric、复杂抢占和云计费。

---

# 18. Resource Reservation 怎么防超卖

选择节点后必须原子预占。

Redis Lua 示例：

```lua
local cpu = tonumber(redis.call('HGET', KEYS[1], 'free_cpu'))
local mem = tonumber(redis.call('HGET', KEYS[1], 'free_mem'))
if cpu < tonumber(ARGV[1]) or mem < tonumber(ARGV[2]) then
    return 0
end
redis.call('HINCRBY', KEYS[1], 'free_cpu', -tonumber(ARGV[1]))
redis.call('HINCRBY', KEYS[1], 'free_mem', -tonumber(ARGV[2]))
redis.call('HSET', KEYS[2], 'node_id', ARGV[3])
return 1
```

但：

> Redis 是短期调度视图，MySQL Reservation + Agent 实际状态 + Reconciliation 才能恢复事实。

---

# 19. RabbitMQ 为什么需要：低 QPS 也有异步问题

这里 MQ 不是为了“用户很多”。

它解决：

```text
Submit 解耦
Batch Backpressure
Post Process
Report Generation
Retry / DLQ
Worker 隔离
```

MQ 消费者不等 Solver 跑 4 小时再 ACK。

正确 ACK 边界：

```text
收到 SUBMIT_EXECUTION
→ Agent 接受并返回 externalExecutionId
→ DB 持久化成功
→ ACK
```

后续几个小时由 Agent/Monitor 管。

---

# 20. Outbox：DB + MQ 双写

危险窗口：

```text
DB: READY → QUEUED commit 成功
↓
RabbitMQ publish 失败
↓
Job 永久卡 QUEUED
```

解决：

```text
Transaction:
  UPDATE Job
  INSERT Outbox
COMMIT

OutboxPublisher:
  NEW → RabbitMQ → Confirm → SENT
```

Outbox 可能重复 Publish，因此 Consumer 仍必须幂等。

---

# 21. 最典型后端 Bug：重复启动 Solver

故障链：

```text
Consumer 收消息
→ Agent 启动成功
→ Worker 在 DB 回写/ACK 前崩溃
→ MQ 重投
→ 第二次启动
```

最终设计：

```text
execution_id / attempt_no 唯一
DB CAS: QUEUED → DISPATCHING
Idempotency-Key: job-{id}-attempt-{n}
Agent 保存 key → externalExecutionId
重复 submit 返回原 externalExecutionId
```

Pilot 最终口径：

```text
Duplicate submit blocked = 19
修复后由 MQ redelivery 引起的 confirmed duplicate Solver run = 0
```

---

# 22. Reconciliation：为什么事件驱动还要对账

正常：

```text
Agent Event → Backend
```

兜底：

```text
每 120 s 扫描 RUNNING / UNKNOWN
→ query externalExecutionId
→ 状态修正
```

Pilot 自动修正 31 个 Stale Job。

重要：

> Agent 掉线不等于 Solver 一定停了，所以先 UNKNOWN/SUSPECT，不立即重跑昂贵任务。

---

# 23. Altair 模型文件怎么处理

## `.hm` 和 `.fem`

```text
.hm  = HyperMesh 前处理源模型/工程资产
.fem = OptiStruct Solver Deck（典型入口）
```

平台同时登记：

```text
SOURCE_MODEL
SOLVER_INPUT
```

不把 `.hm` 当成统一 Solver Input。

## Case Package

```text
case/
├─ main.fem
├─ includes/
│  ├─ body.fem
│  ├─ material.fem
│  └─ loadcase.fem
└─ manifest.json
```

## INCLUDE

OptiStruct 官方支持：

```text
-check
-inventory
-incpath
```

平台可以借助这些能力做：

```text
Dependency Inventory
Path Allowlist
Relative Path Normalize
Precheck
Input Snapshot
```

---

# 24. 为什么大文件不穿 Spring Boot

CAE 数据可能是 GB 级甚至更大。

所以：

```text
MySQL → Metadata
NFS → Solver Workspace / Raw Result
Object Storage → Report / Screenshot / small package（可选）
```

后端只负责受控 URI、权限和 Metadata。

---

# 25. Result / Report Pipeline

```text
Solver Success
→ raw result ready
→ POST_PROCESS_REQUESTED
→ Python Post Worker
    ├─ .out / text parser
    ├─ HyperView result API
    └─ HyperGraph / curve
→ result.json
→ images / csv
→ Java ResultIngest
→ REPORT_REQUESTED
→ template engine
→ PPTX draft
→ engineer review
```

## HyperView 能做什么 `[WEB-VERIFIED]`

Altair 2024 Python API 官方已经提供：

```text
load model/result
result scalar settings
view orientation
CaptureImageTool
Report/PPTX example
```

因此自动截图和 PPTX 草稿路径在项目时间上成立。

---

# 26. 报告为什么必须版本化

保存：

```text
solver_profile_version
parser_version
post_profile_version
report_template_version
metric_definition_version
```

发现 Parser Bug：

```text
不重跑 Solver
→ Re-process old Result
→ Regenerate Report
```

这也是结果资产化的价值。

---

# 27. Leadership / 部门验收到底怎么判断成功

最终不是看：

```text
“是不是用了 Redis / RabbitMQ”
```

而是五类 Exit Criteria：

```text
1. 标准任务可以端到端跑真实 Solver
2. 批量任务不需要重复手工操作
3. Node/Backend 异常后状态能恢复
4. 结果和报告能追溯 Model/Condition/Version
5. Pilot 用户愿意把高频标准工况放到平台
```

Quantitative：

```text
Accepted actions     = 732
Execution Jobs       = 1,428
Submit defect        = 8.70% → 2.87%
Single operation     = 12.4 → 4.1 min
50-case Batch        = 47.6 → 5.8 min
Structure report     = 86.4 → 18.2 min
Estimated manual saving = 151.0 person-hours
Feedback closed      = 56 / 63
```

---

# 28. 我的工作量怎么讲才可信

不要说：

> “后端、前端、Worker、所有 Parser、所有报告都是我写的。”

正确：

> 我拥有的是 Java Control Plane 和跨模块 Contract。专业 Owner 决定 Solver 领域规则，B 写 Agent/Post Worker 主体，A 写前端，F 做测试/模板/部署；我把它们通过 Job/Execution/Artifact/Report Contract 串起来，并负责最难的事务、幂等、调度和恢复逻辑。

---

# 29. 推荐简历版本

**上汽通用五菱汽车股份有限公司｜仿真工程师｜CAE 内部提效项目组长 / 核心后端｜2024.12–2025.07**

- 面向刚度/模态、NVH、碰撞和 CFD 共用公司 CAE 计算服务器、任务提交和结果整理依赖个人脚本的问题，带领 7 人小组建设仿真任务调度、结果管理与自动报告平台；以结构/模态作为完整 Reference Path，其余专业按 Pilot 深度接入统一 Job 控制面。
- 基于 **Java 17 / Spring Boot 3.3 / MySQL / Redis / RabbitMQ** 设计 Job/Execution 状态机、Transactional Outbox、幂等 Submit、Node Heartbeat、CPU/Memory 原子预占与 Reconciliation；通过 Python Compute Agent 对接公司自有计算节点，解决重复启动 Solver、节点掉线、长任务状态不一致等问题。
- 针对 Altair 流程将 HyperMesh 源模型与 OptiStruct Solver Deck 分层管理，引入 Case Manifest、INCLUDE 依赖预检和不可变 Input Snapshot；Solver 完成后由 HyperView/Python Post Worker 自动提取 KPI、生成标准云图/曲线并按工况模板形成报告草稿。
- 统一复盘口径下，35 个工作日 Pilot 共 `732` 次有效提交动作生成 `1,428` 个 Execution Job；单任务操作 `12.4→4.1 min`，50-Case Batch `47.6→5.8 min`，可避免提交失败 `8.70%→2.87%`，32 份结构标准报告人工整理 `86.4→18.2 min`，按不重复动作估算减少约 `151.0 person-hours`。

---

# 30. 一分钟项目介绍

> 我原来做 CAE 仿真，公司本身有服务器、共享存储和 OptiStruct 等 Solver，但工程师和算力之间缺少统一业务层。不同专业都要重复做 Case 路径检查、选服务器、填资源、远程启动、查状态、找结果和整理报告。2024 年底我们 7 个人做了一个科室提效项目，我是组长，同时负责核心 Java 后端。
>
> 我把不同专业统一抽象成 Project、Model、Condition、Job、Execution 和 Artifact，后端用 Java 17、Spring Boot 3.3、MySQL、Redis 和 RabbitMQ；平台自己管理 Pilot 范围内的计算节点，通过 Agent Heartbeat 获取 CPU/Memory，Scheduler 做资源预占和节点选择。长耗时 Solver 不占着 HTTP 或 MQ 消息，Agent 启动成功拿到 externalExecutionId 后 MQ 就 ACK，后续靠状态事件和 Reconciliation 跟踪。
>
> 结构/模态是我们做得最完整的链路：HyperMesh 导出的 `.fem` 在提交前检查 INCLUDE 和 Input Snapshot，OptiStruct 完成后 Post Worker 用 HyperView 自动切 Subcase、Contour 和视角生成图片，再根据模板形成 PPT 草稿。其他 NVH、碰撞、CFD 主要接入统一任务和关键结果，自动化深度不同。
>
> 统一复盘口径下，35 个工作日 Pilot 有 732 次有效提交动作，生成 1,428 个执行 Job；单任务人工操作从 12.4 分钟降到 4.1 分钟，50 Case Batch 从 47.6 分钟降到 5.8 分钟，标准结构报告从 86.4 分钟降到 18.2 分钟。项目没有让 Solver 算得更快，主要减少的是工程师非核心重复操作。

---

# 31. 三分钟项目介绍：后端岗位版

> 项目背景是仿真集成科有刚度模态、NVH、碰撞和 CFD 等专业，大家会共用公司内部 CAE 计算服务器。以前 Solver 本身已经很成熟，但周边流程比较碎：工程师自己维护共享目录、个人脚本、服务器选择、运行状态和报告，所以任务一多以后就会有版本找错、路径遗漏、服务器热点、重复启动和结果难追踪的问题。
>
> 我们从 2024 年 12 月开始做科室级平台，一共 7 人，我是组长和核心 Java 后端。第一阶段只跑一个结构 OptiStruct Case，验证 Web→Agent→Solver→Result。实际使用后大家总选熟悉的服务器，所以二月份才加入 Node Registry、Heartbeat、Resource Reservation 和 Scheduler；三月份又遇到 `.fem` 在本机能跑但服务器找不到 INCLUDE 的问题，于是从“一个文件”升级为 Case Package + Manifest + dependency precheck。
>
> 到四月份多专业和 Batch 起来以后，我们才引入 RabbitMQ，让 Submit、PostProcess、Report 变成异步命令。我认为项目最典型的后端难点是外部 Solver 的副作用不能被数据库事务覆盖。例如 Worker 已经让 Agent 启动 Solver，但在写 DB/ACK 前宕机，MQ 重投后就可能再启一个。我最后用 execution attempt、DB CAS、Idempotency-Key 和 Agent 侧 key→externalExecutionId 映射解决；状态回调丢失则通过 Reconciliation 定期对账。
>
> 结果侧 Java 不直接解析 H3D，而是 Python Post Worker 调 HyperView/专业脚本。结构标准工况把 Subcase、Result Type、Component、View 都写进版本化 PostProfile，自动生成 KPI、云图和 PPT 草稿，最终工程结论仍由 CAE 工程师审核。
>
> 我们内部 Pilot 的统一复盘口径是 24 个授权用户、21 个实际使用用户、35 个工作日、1,428 个执行 Job。结构/模态是完整链路，其他专业是不同深度的 Pilot。项目成果主要是人工操作和流程质量，不是虚构 Solver 算法提速。

---

# 32. 互联网后端面试官高频验真问题

## Q1：项目输入输出是什么？

> 输入是模型版本、工况、Solver/Resource Profile 和 Case Package；同步返回 JobNo/状态，最终异步产出 Execution 状态、日志、Result URI、结构化 KPI、图片/曲线和报告草稿。

## Q2：你具体负责什么？

> 我负责 Java Control Plane：Job/Execution Domain、DB、状态机、MQ/Outbox、幂等、Scheduler/Reservation、Agent Contract、Reconciliation、Result/Report Metadata；不是所有专业 Parser 都由我写。

## Q3：项目真的应用了吗？

> 是内部 Pilot，不是公网产品。2025-06-02～07-18 共 24 个授权用户、21 个实际使用用户，732 次有效提交动作生成 1,428 个 Execution Job。

## Q4：有前端后端吗？

> 有。Vue 3 做内部 Job Portal；Spring Boot 是业务控制面；Python Agent/Post Worker 负责计算环境和 CAE API 适配。

## Q5：部署在哪里？

> 公司内网。Nginx + Spring Boot Docker 服务，MySQL/Redis/RabbitMQ 使用内部服务环境，Agent 在受控计算节点/执行网关；Solver 本身仍运行在原有 CAE Linux 环境。

## Q6：最大难点是什么？

> 外部长任务最终一致性：重复消息不能重复启动 Solver，事件丢了状态也要收敛。方案是 Outbox + at-least-once + idempotent submit + state CAS + Reconciliation。

## Q7：最大的业务难点？

> 不同专业差异很大，但平台不能把所有逻辑写成 if/else。我们统一生命周期，把差异放在 JobTemplate/SolverAdapter/ResultParser/PostProfile/ReportSpec。

## Q8：为什么低 QPS 还需要 MQ？

> 复杂度来自任务持续数小时和外部依赖，不来自 Web QPS。MQ 用来解耦 Submit/Post/Report、做 Retry/DLQ/Backpressure。

## Q9：为什么 Redis？

> Node heartbeat/hot resource view、短期 reservation、幂等辅助。MySQL 仍是 Job/Execution 真值。

## Q10：为什么不微服务？

> 7 人、24 个 Pilot 用户不需要拆十个服务。采用模块化单体 + 独立 Worker/Agent，只把真正需要运行时隔离的计算环境适配拆出去。

## Q11：项目 QPS 多少？

> 真实内部系统 QPS 不是价值指标。主要看 Job throughput、queue wait、batch 操作时间和报告时间。本地可以另做 API 并发测试，但不会冒充生产流量。

## Q12：如果 Backend 挂了，运行中的 Solver 会停吗？

> 不会直接停。Solver 是节点进程，Backend 是 Control Plane。恢复后通过 externalExecutionId 对账并恢复状态。

## Q13：Node 掉线为什么不直接重跑？

> Agent 心跳丢失不等于 Solver 停止。直接重跑可能制造两个昂贵 Execution，所以先 UNKNOWN，再对账。

## Q14：有做测试吗？

```text
Unit: StateMachine / Scheduler / Validator
Integration: MySQL/Redis/RabbitMQ/Outbox
Failure Injection: duplicate message / Agent disconnect / Backend restart
E2E: Mock Solver + 真实结构 Golden Case
Report Regression: KPI / image provenance / template
```

## Q15：项目成果怎么量化？

> 统一说单位动作指标：单任务 12.4→4.1min；50 Case Batch 47.6→5.8min；可避免提交失败 8.70%→2.87%；结构标准报告 86.4→18.2min。按 708 单任务动作、24 Batch、32 报告不重复计算约 151.0 人时。

---

# 33. 面试官继续沿代码追问时

## Controller 到 Agent

```text
JobController
→ JobApplicationService
→ JobRepository / ExecutionRepository
→ OutboxRepository
→ commit
→ OutboxPublisher
→ RabbitMQ
→ SubmitCommandConsumer
→ SchedulerService
→ ComputeAgentClient
→ externalExecutionId
```

## 核心类

```text
JobApplicationService
JobStateMachine
JobRepository
ExecutionRepository
OutboxPublisher
SubmitCommandConsumer
IdempotencyService
NodeRegistryService
ResourceReservationService
SchedulerService
ComputeAgentClient
ExecutionReconcileTask
ArtifactService
ResultIngestService
ReportOrchestrator
```

---

# 34. 如果面试官问“你们如何协作”，60 秒标准回答

> 我们没有按“谁写哪个脚本”来协作，而是围绕 CAE Job 生命周期定义 Data Contract。结构、碰撞、CFD 的专业 Owner 负责给我 JobTemplate、Golden Case、资源范围、成功失败判定、Result 和报告需求；我负责把这些领域事实收敛成统一的 Job/Execution/Artifact/Metric/ReportSpec，并实现状态机、调度和可靠性。Python 同事消费我定义的 Execution Contract，不直接改 Job 状态；前端消费统一状态和错误码，不自己理解 Solver。每周一我们做 30 分钟项目同步，接口变化再拉小会，每 2～3 周拿真实 Case 给科室负责人和专业代表做里程碑 Demo。一个比较典型的协作问题是 Resource Profile：最开始大家想自由填 CPU/Memory，后来我们让专业 Owner 定 Default 和 Allowed Range，由后端版本化并做资源预占，这样既保留专业灵活性又保证调度可控。

---

# 35. 如果面试官问“领导怎么验收”，30 秒回答

> 不是看我们写了多少功能。负责人每个里程碑都要求真实 Case 端到端演示。最终验收固定演示 6 个场景：结构单任务、50 Case Batch、重复提交幂等、节点异常恢复、Result Re-process/Report Regenerate、跨专业历史查询。除此之外还看 Pilot 的单位操作耗时、可避免失败率和反馈闭环。最后 63 条 Pilot Issue 关闭 56 条，其余 7 条明确进下一阶段 Backlog。

---

# 36. 如果面试官问“内部用户怎么反馈”，30 秒回答

> 最有价值的反馈其实都不是“页面好不好看”。结构组要求不要让大 Case 重新上传，所以改成 NFS Path Registration；碰撞组明确要求 Runtime Failed 不要无脑 Retry；CFD 组最需要的是 Batch；报告审核人员发现自动截图必须绑定 Subcase 和 Execution；负责人要求不要用 QPS 当成果，而看工程师时间和失败率。这些反馈直接改变了后面的产品优先级。

---

# 37. 公开资料校准结论

1. **Spring Boot 3.3.0** 于 2024-05-23 GA，**3.3.6** 于 2024-11-21 发布，因此项目 2024-12 使用 3.3.6 时间匹配。
2. HyperMesh **2024 是第一版 Python API**；官方同时说明 Tcl 是长期存在的成熟 API，且 2024 Python API 仍有覆盖限制，所以项目不应声称所有前处理都完全用 Python 重写。
3. OptiStruct 官方支持命令行运行 `.fem`、`-check`；`-inventory` 会产生包含所需输入文件的清单；`-incpath` 可以辅助 INCLUDE 搜索。
4. HyperView/Framework Python API 官方有结果加载、视图控制和 `CaptureImageTool`；Report API 官方示例可以生成 PPTX。因此自动后处理和报告草稿技术上成立。
5. 公开后端面经反复追问项目背景、个人负责、上线/用户、部署、难点、性能、Redis/MySQL/MQ 的实际用途，所以本文把“项目验真”放在八股之前。

---

# 38. 最终最重要的边界

不要说：

> “我们 7 个人做了一个公司级 HPC 云平台。”

应该说：

> “公司已有算力和 Solver，我们把科室最常用的计算流程做成统一控制面。”

不要说：

> “四个专业的报告都完全自动化。”

应该说：

> “结构/模态是完整 Reference Path，其他专业按 Pilot 深度接入统一 Job 和关键结果。”

不要说：

> “1,428 个 Job 每个都节省 8.3 分钟，所以省了 197 小时。”

应该说：

> “按 708 个单任务操作、24 个 Batch 和 32 份报告分开核算，约 151.0 人时，避免 Batch Child Job 重复计数。”

不要说：

> “自动报告替工程师做最终判断。”

应该说：

> “自动的是标准数据、截图、变化率和模板，工程结论仍由 CAE 工程师审核。”


---

# 39. 公开校准资料（用于证明技术时间线和面试问题方向，不用于证明公司内部 KPI）

## 技术时间线

```text
Spring Boot 3.3.0 GA        2024-05-23
Spring Boot 3.3.6           2024-11-21
RabbitMQ 3.13.0             2024-03-11
Redis 7.2                    2023-08-15
HyperMesh Python API         first release in HyperMesh 2024
```

## Altair 能力

官方 2024 文档可确认：

```text
HyperMesh Python API 可自动化 HyperMesh workflow，并与长期存在的 Tcl API 并存；
OptiStruct 支持命令行执行 .fem；
-check 用于输入检查；
-inventory 生成运行需要的输入文件清单；
-incpath 可以配置 INCLUDE 搜索路径；
HyperView/Framework Python API 提供结果加载、视图和 CaptureImageTool；
Report Python API 有从模板生成 PPTX 的官方示例。
```

## 后端项目面试方向

公开后端面经高频追问：

```text
项目背景 / 整体架构
你具体负责什么
项目有没有上线、用户和数据指标
部署了多少实例
最大挑战、怎么解决
MySQL/Redis/MQ 为什么这样用
系统瓶颈、压测和扩容
如果重做怎么优化
```

所以面试准备顺序应是：

```text
业务验真 → Ownership → 输入输出 → 上线/用户 → 架构 → 难点 → 代码 → 指标 → 复盘
```

而不是从 Redis 八股开始。
