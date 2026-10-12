# M9 前端实施准备

状态：已实现并通过完整门禁与真实浏览器验收。接口与当前实现契约见 `docs/frontend.md`。下文保留前期设计依据，历史缺口表不代表当前仍未实现。

依据：`docs/reference/Codex提示词_CAE仿真任务调度结果管理与自动报告平台_完整复现.md` 第 9、14、25、26、28、29、40、50、52 节及 Milestone 9。保持 Vue 3 + TypeScript + Vite + Pinia + Vue Router + Axios + Element Plus + ECharts；这些依赖已经锁定安装，不引入另一套 UI 库或图表库。M9 实施必须在 M8 完整门禁和稳定提交之后开始。

## 现有真实能力与缺口

2026-10-11 对本地 Nginx `/api/v1` 的只读请求核对了用户、项目、版本、Case、资源档位、节点、License 与 Job 列表。部署版本仍为 M6，因此 `/cases` 当时没有 M7 新增的 `subcase` 字段；M7 工作树代码已经加入该字段。不要把“源代码已写入”当成“运行环境已验收”。

| 场景 | 现有接口/行为 | M9 所需动作 |
| --- | --- | --- |
| 登录身份 | `GET /auth/me`，HTTP Basic，返回 `username` 和 `roles` | 登录表单、会话内认证、路由守卫、统一 401/403 处理 |
| 项目/模型版本 | `GET /projects`、`GET /models?projectId=`、`GET /model-revisions?projectId=`；已有成员过滤 | 表单联动，使用实际 `projectId`、`modelId`、`revision`，切换项目时清理旧值 |
| Case | `GET /cases`，字段 `casePath/caseId/analysisType/conditionCode/modelRevision/synthetic`；M7 新增 `subcase` | 选择公开 Case，联动分析类型、工况和模型版本，展示 Synthetic 来源与 Subcase |
| 资源 | `GET /resource-profiles`、`GET /license-pools`；Profile 带版本及允许学科 | 普通用户仅选 enabled、支持当前学科的 Profile，显示 CPU/内存/时限/License |
| 创建 | `POST /jobs` 返回 202 与 `jobNo/status/links` | 建 Job 与 Submit 两个确认后的请求；补创建幂等契约，防止创建响应超时后重复 Job |
| 筛选列表 | `GET /jobs`，`projectId/analysisType/conditionCode/status/submitter/dateFrom/dateTo/page/size`，返回 `items/total/page/size` | 完整筛选、URL 保存条件、分页、详情链接，处理日期口径 |
| 执行操作 | `POST /jobs/{jobNo}/validate|submit|retry|cancel`；submit/retry 支持 body `idempotencyKey` | 可执行状态对应按钮、重复点击抑制、超时重用原键、操作后重新 GET |
| 优先级 | `PATCH /jobs/{jobNo}/priority`，Lead/Admin 服务端授权 | Lead/Admin 修改，冲突/失败后重读，不先假装成功 |
| 详情/时间线 | `GET /jobs/{jobNo}`、`GET /jobs/{jobNo}/events` | 展示平台状态、stage、failure、事件；远端 UNKNOWN 与取消待确认分别解释 |
| 执行历史 | `GET /jobs/{jobNo}/executions`，安全移除 `request` | 显示全部 attempt、节点、PID/identity、进度、时间、错误，选择查看某次执行 |
| 指标与解析 | M7 `GET /jobs/{jobNo}/metrics?parserVersion=v1|v2`、`GET /parses`、`POST /reparse` | 显式版本选择，不把多版本指标合成一组；显示单位、condition、subcase、质量与 PILOT |
| 工件 | M7 `GET /jobs/{jobNo}/artifacts`、Job scoped 与 `/artifacts/{id}/download` 两个下载入口 | 认证 Blob 下载/图片预览、SHA 与版本 metadata、分页或分组，释放 Object URL |
| 节点 | `GET /compute-nodes`、`GET /compute-nodes/{nodeId}`、Admin `PATCH /{nodeId}/drain` | CPU/内存 used、reserved、observed 分开；心跳时间、tags、drain、ONLINE/SUSPECT/OFFLINE |
| Dashboard | 尚无业务聚合接口 | 增加权限过滤的真实聚合及明确口径；不得把前端当前 20 条列表当全量 |
| 实时状态 | 尚无 `/events/stream` | 增加有权限的 SSE，客户端使用带 Basic header 的 fetch；重连后 GET 真值 |
| Solver 日志 | 没有受控 tail 接口 | 增加 Job/Execution scoped、清洗、限行限字节的 tail；不让浏览器提供路径 |
| Report/Baseline | M8 范围，当前不能调用不存在的接口 | M8 验收后接真实报告生成、版本、下载、Baseline 和 Review；缺失先补后端 |
| Batch | M10 范围 | M9 不放没有行为的 Batch 页或按钮；M10 同步接 parent/child 汇总 |

API 成功响应当前是直接对象或数组，并没有统一 `data` 包装。错误响应包含 `code/message/traceId/details`，部分包含 `stage/retryable`；Axios 层按真实契约读取。

## 用户页面与操作链

### 应用框架与认证

路由：`/login`、`/` Dashboard、`/jobs`、`/jobs/new`、`/jobs/:jobNo`、`/compute-nodes`、`/reports`。页头提供当前用户/角色、项目切换、退出；侧导航只放已可用页面。404 显示可返回链接，不默默跳转 Dashboard。

用户提交用户名/密码后调用 `/auth/me`，仅成功后保存身份和权限。认证资料默认只在内存中保持，不写 localStorage、URL query、下载链接或日志；页面刷新重新登录是可接受的公开 Demo 行为。Axios 与 fetch SSE 从同一个 session store 读取 Authorization。退出中止流、停止轮询、清理缓存及 Blob URL，防止换账号显示上一位用户的数据。

账户示例来自已有 Demo seed：engineer / engineer123、lead / lead123、admin / admin123。登录页可列演示账户，但实际输入和失败处理必须走后端。页面长时间挂起后 401 要停止自动重试并返回登录，403 保留页面并显示无权限。

### Dashboard

项目筛选和统计时间窗作用于真实 Job 聚合；节点负载是当前观测，标明它不是所选时间窗的历史平均。六个必需指标都给出实际值与口径：Running、Waiting、Failed、Compute Node Load、Success Rate、Average Queue Time。没有样本显示 `—` 与样本数 0，不能显示伪造的 100% 成功率或 0 秒等待。

ECharts 至少显示按学科的状态分布和三节点资源负载；所有点来自接口。点击 Waiting/Failed 卡片跳转带 status 筛选的 Job 列表。图表 resize 使用 ResizeObserver，卸载 dispose，避免路由切换叠加实例。

### Create Job

表单顺序：Project → Model Revision → Analysis Type → Condition → Demo Case → Resource Profile → Priority。Case 的 manifest 工况、revision 和 subcase 是可见约束，不允许选择 Torsion 指标却提交 Modal Case。项目切换触发新的 revisions 请求，旧请求晚到时不得覆盖当前选项。NVH/Crash/CFD 显示 PILOT，Structure/Modal 显示完整流程覆盖。

Profile 显示当前 CPU、memoryGb、walltimeMin、licenseTokens 与 version；普通工程师不直接编辑 512 CPU 等任意资源。表单没有 shell command、宿主机绝对路径或可执行脚本字段。Priority 使用已有 0–100 服务端范围。

提供“创建并提交”主按钮与“仅创建”操作。创建成功取得 `jobNo` 后，后续超时只重试该 Job 的 submit，不能重新创建。提交成功导航详情并 GET。创建 202 仅说明控制面接受，不代表已经运行或成功。

普通工作流不默认暴露 failureMode 等工程注入参数；需要演示故障时由明确标识的 Demo 控件或 failure-demo 脚本生成。不得把实现参数放进日常业务必填流程。

### Job List

六种必需筛选均使用服务端，日期选取支持清除，page 默认 0、size 20，上限 100。显示 JobNo、项目/模型、学科、工况、状态、当前阶段、submitter、priority、创建时间。时间使用 `Asia/Shanghai` 格式并明确时区；链接保留筛选与页码。

加载失败显示重试按钮及 traceId，不用空表冒充“没有任务”。刷新过程中保留既有表格并标识更新时间。用户改变筛选或页码时 AbortController 取消旧请求，或使用 request sequence 丢弃旧响应。

### Job Detail

顶部：JobNo、状态中文说明与原始状态码、stage、分析类型/工况/Subcase、当前执行 attempt、目标节点、资源档位快照、创建/开始/结束时间、最后刷新时间。未知状态保留原码，不映射成失败。

分区：时间线、执行与资源、日志、指标与解析版本、图片与工件、报告。当前执行通过 `currentExecutionId` 关联 executions，不用数组第一项猜测。Attempt 历史不覆盖，每次执行的 failure/raw code、PID/identity 和节点独立显示。资源已释放与 Solver 状态分开，不能仅以 Job 成功假定 release。

取消只显示后端确认结果：`CANCEL_REQUESTED` 是已请求取消、`CANCELLED` 是已确认结束；失败或 UNKNOWN 时不在本地改成 CANCELLED。NO_RESOURCE 是等待原因而非 terminal failure。平台 `failureCode` 与 `rawSolverCode/rawMessage` 区分展示。

指标表按 parserVersion 读取，显示 canonical code/name、数值或文本、unit、dimension、status、conditionCode、subcase。数字格式仅用于显示，不重算 Backend 的 comparison。Reparse v2 操作保留原有 v1 和报告版本，不出现新的 Solver attempt。

日志面板允许选择执行 attempt，默认 200 行，最多 500 行。使用纯文本 `<pre>` 或文本节点，禁止 v-html；日志更新保留用户阅读位置，只有用户启用跟随时滚到底。运行中轮询可每 3–5 秒一次，页面隐藏暂停，返回时刷新。

图片预览只读取类型为 PNG 的已登记 artifact。先 Axios `responseType: 'blob'`，校验服务端成功响应和 MIME，再创建 Object URL；替换/卸载时 revoke。图旁展示 condition/subcase/resultType/view/parserVersion/postVersion，支持下载原始文件。路径 `uri/workspace/agentUrl` 不是给浏览器直接打开的入口。

### Compute Nodes 与 Resource

节点 CPU `cpuUsed/cpuReserved/cpuTotal` 和内存 `memoryUsedGb/memoryReservedGb/memoryTotalGb` 属于 admission ledger；`observedCpuUsed/observedMemoryUsedGb` 属于 Agent 观测，分别标识。OFFLINE 节点仍可能有 active reservation，不能画成零资源。

展示 solverTags、workspaceReachable、runningJobs、lastHeartbeat、drained；心跳年龄由实际时间计算。Admin drain/恢复请求成功后重新 GET；服务端已有 Admin 强制校验。若提供 Resource Profile 管理入口，仅 Admin 可进入，并携带当前 version 执行 PUT/DELETE，409 后提示刷新。管理页不在 M9 必需主流程中抢占报告和 SSE 实施时间。

### Report

`/reports` 提供 Project → Job 选择以及该 Job 报告版本列表；无需先新增全局报告搜索接口才完成必需演示。也可从 Job Detail 直接打开对应报告。表格显示 reportVersion/parserVersion/postVersion、condition/subcase、template、DRAFT/REVIEWED/APPROVED、生成时间、Baseline 与下载入口。

Generate 使用 M8 真实 API，选择已完成 parse 版本；Baseline 候选必须服务端过滤项目、模型、analysis、condition、subcase、unit/dimension 和可用解析版本。选定后展示后端返回的 baseline/current/delta/deltaPercent。baseline 为零时 percent 为 `N/A`，缺失指标不画 0。比较数值不得由前端/PPTX/LLM重新计算。

Engineer 能生成 Draft；Lead/Admin 能选择 Baseline 与 Review；APPROVED 只允许 Admin 的显式操作。每次成功/失败后读取服务端 report 状态，不能“生成即签核”。下载使用认证 Blob 与服务端文件名；报告状态和 Job 状态独立，例如一个已成功 Job 的新 v2 草稿不会让旧 v1 消失。

## 最小后端新增契约

### Dashboard 聚合

建议 `GET /api/v1/dashboard?projectId=&dateFrom=&dateTo=&timeZone=Asia/Shanghai`，返回 `asOf/window/counts/byAnalysisType/nodes/successRate/averageQueueSeconds`。所有 Job 聚合沿用 ProjectAccess + 成员 EXISTS 过滤；显式无权 projectId 返回 403，不静默混入别的项目。按 createdAt 选择时间窗，接口与页面明确这一口径。

建议口径：

- `running` = RUNNING；可另返回 SUBMITTING/SUBMITTED 等细分，以免把等待 Agent 确认算成真实运行。
- `waiting` = QUEUED + WAITING_RESOURCE；返回两项明细并分别可跳列表，未提交的 CREATED/READY 不计入排队。
- `failed` = 五个 *_FAILED 状态；UNKNOWN 单独计数，CANCELLED 单独计数。
- `successRate` = SUCCESS / (SUCCESS + 上述 failed)，返回 numerator、denominator、percent；排除 active 与 CANCELLED。
- `averageQueueSeconds` 返回 value 与 sampleCount。不能使用现有 startedAt − submittedAt：submittedAt 是 Agent 已接受后才写入，会漏掉资源等待。

等待均值的可靠来源可以是每个 execution 前最近一次 `JOB_SUBMIT_REQUESTED` event.createdAt → 该 execution.startedAt；用 execution 对应 attempt，不把 retry 与首次运行混在一起。无 startedAt、不合理负时差的样本排除并返回排除数。另一可接受方案是新 migration 加 queued_at，但要在进入 QUEUED 时记录并明确 retry 语义，不改旧 Flyway checksum。

现有 Job dateFrom/dateTo 由 LocalDate 转 SQL 边界，隐含 UTC。若 M9 显示中国日期，应给 Job list 与 Dashboard 增加同一可校验 timeZone，先转当天当地午夜/次日午夜对应的 UTC Instant 再查询；默认参数保留 UTC 兼容性。也可新增显式 `createdFrom/createdBefore` ISO Instant 参数。避免前端选 10 月 11 日而后端筛另一个 UTC 日。日期端点不能使用 UTC 日午夜加固定 24 小时来假装支持所有时区。

### 创建请求幂等

当前只有 `SubmissionRequest.idempotencyKey` 与 `(job_id,idempotency_key)` 唯一约束；CreateJobRequest 没有这个字段。因此“创建超时保留 UUID”需要真实后端支持。

建议 POST `/jobs` 接可选 `Idempotency-Key` header（兼容已有无键客户端），持久化 `(actor,idempotency_key)` 唯一请求记录、稳定 normalized fingerprint、created jobId。同键同 body 返回同 jobNo；同键不同 body 返回 409；记录与 Job/audit 同事务。并发两次创建必须只有一个 Job，不依赖 Redis TTL 保证持久唯一性。鉴权/项目权限在重放时仍检查，不因已命中键跳过当前权限。

UI 在一次表单提交之前生成 `crypto.randomUUID()` 并保存原 body。15 秒网络超时、浏览器未获响应、重连重试都使用原键和 body；编辑表单后显式开始新请求并生成新键，不把旧键套在变更参数上。Submit/retry 也分别保存自身 idempotencyKey。一个 UI click 对应 create key 与 submit key 两个不同用途，不能用刷新或超时无条件创建新 UUID。

### SSE

规范接口 `GET /api/v1/events/stream?jobNo=&projectId=`，`Content-Type: text/event-stream`。建议所有连接限制为有权限的 job/project 或当前用户可见项目；注册时使用 ProjectAccess，不接受任意前端 username。事件只包含 eventId/type/jobNo/jobId/status/version/occurredAt 等安全通知，不包含任意文件路径、内部请求或凭证。

实现可以使用 bounded SseEmitter registry + 定时读取已提交 `cae_job_event` cursor。不要让 SSE 消费者竞争现有可靠 command queue，也不要从未提交事务发送状态。事件按 durable id 递增；Last-Event-ID 仅用于减少重复，重连后仍 GET Job/相关列表。连接注册时取 cursor 然后读取状态/增量，避免“GET 与 subscribe 之间”掉事件。首个 `ready` 后客户端做一次 GET 也能覆盖这个窗口。

每 15 秒 heartbeat，连接数有上限；完成/错误/超时/客户端 abort 都移除 registry。慢客户端丢弃冗余通知或断开后让它 GET，不能无限积压内存或长占事务。节点负载轮询走独立 GET，不假定 Job SSE 包含 heartbeat。

客户端使用 fetch：`Authorization: Basic ...`、`Accept: text/event-stream`、AbortController；原生 EventSource 无法添加 Basic header，不得把 token/password 塞 query。流解析支持 UTF-8 分块、CRLF、跨块行、comment heartbeat、多行 data 与空行事件分隔。401/403 是停止条件；网络失败指数退避加 jitter（如 1/2/4/8/15 秒上限）。同页只保持所需连接，路由离开 abort。

SSE 消息触发合并 GET（约 100–250ms coalescing），不是直接修改本地 authoritative 状态。每次重连 ready 重新 GET；非终态详情同时保留 10–15 秒兜底轮询，页面可见性改变后 GET。显示“实时连接中/重连中/轮询中”与最后同步时间，不能用动画代替真实性。

### 受控 Solver 日志

建议 `GET /api/v1/jobs/{jobNo}/logs?executionId=&lines=200`，返回 `jobNo/executionId/lines/truncated/updatedAt`。默认当前 execution，指定 attempt 必须属于该 Job；先检查 Job 项目权限再文件读取。line 上限 500、读取上限如 256 KiB，返回长度另有硬限制，日志不存在返回有语义的空结果而不是 500。

路径只能由 Job + 已登记 execution workspace 推导 `solver.log`，复用安全相对路径和 symlink 拒绝；不接收任意 path。运行中的日志尚未注册为 artifact，也应该能安全 tail，不以“先出现 SOLVER_LOG artifact”作为前提。移除 ANSI/control 字符，屏蔽服务 token/Authorization/可识别密码字段，保留必要工程诊断；不加载整个超大日志。下载终态原始日志可继续使用已登记工件下载权限。

### 热 Job 缓存

规范需要 Redis 热 Job 查询缓存。可在 M9 补 TTL cache，读取前先从 MySQL 检查 job 项目/存在性；状态变更事务 afterCommit 失效、版本阻止旧读回填覆盖新状态。Redis 故障回退 MySQL。所有 submit/retry/cancel/parse/post/report 判断继续 lock/MySQL，不能从 UI cache 决定是否 resubmit 或 release。缓存无法取代 GET 真值的权限检查。

## 权限矩阵

| 操作 | Engineer | Lead | Admin | 服务端验收 |
| --- | --- | --- | --- | --- |
| 项目内查看 Job/metrics/log/artifact/report/SSE | 有项目权限 | 有项目权限 | 全部 | 无项目权限接口与 SSE 拒绝 |
| 创建并提交、重试、取消 | 自己 Job | 自己 Job | 任意 Job | 复用 requireOwner；Lead 不自动获其他人的取消权 |
| 调整优先级 | 不提供 | 项目内 | 全部 | 现有 requireLead；Engineer 直接 API 返回 403 |
| Generate Draft | 项目内 | 项目内 | 全部 | M8 的生成权限保持 ProjectAccess |
| 选择 Baseline、Review | 不提供受限操作 | 项目内 | 全部 | M8 接口强制 Lead/Admin 与同项目 |
| Approve | 不提供 | 不提供 | 允许 | 显式手动状态转换与 Audit |
| Node drain、Resource Profile 管理 | 只读 | 只读 | 允许 | 现有 Admin 检查；前端隐藏不能替代此检查 |

用户角色及 submitterId 来自 auth/me 和 Job DTO，不能从浏览器任意输入决定权限。界面判断只改善体验，后端校验必须覆盖直接 API 调用。

## 实施分工边界

M9 开始前先固定 M8 报告/比较 DTO 与真实示例，随后可并行三条实现线；父 agent 负责合并、门禁、集成问题与验收结论。

1. **后端 read model / SSE / logs owner**：`dashboard/**`、`events/**`、受控 log service/controller；必要 Job 查询日期口径、热缓存及 create-idempotency migration，独占这些 Java 文件。先给前端接口样例和 enum，新增真实 MySQL/权限/SSE 测试。不要改已有 Scheduler/Worker 语义。
2. **前端基础 + Job owner**：`api/**`、`stores/session`、router、App/style、登录、Create/List/Detail、SSE helper、Blob helper、类型/状态映射。共享 helper 签名先约定，其他 owner 不编辑这些文件。没有 Stub 数据和固定“已成功”展示。
3. **前端 Dashboard / Node / Report owner**：对应三页及各自组件；调用已经约定的 shared client/session/helpers，不复制认证或 Blob 流程。Report 用 M8 真 DTO，Dashboard 与 Nodes 用真实 read model。
4. **浏览器验收 owner**：只修改 `tests/e2e/frontend_smoke.*` 与 M9 验证文档；用实际浏览器完成下面链路，不跟前端 owner 竞争源码。已有 Chromium 能力可复用；必要 browser runner 仅作为测试工具，不能为了截图扩业务依赖。

主分支不在 M8 门禁前实施以上代码。设计准备文件可以在当前阶段提交，但 README/progress 不提前宣称 M9 完成。

## 必须实际验收的浏览器行为

- Engineer 登录 → 从真实目录选 Structure Current → SMALL → 创建并提交 → 详情看到 WAITING/RUNNING 与时间线 → 正常 SUCCESS → canonical 指标、四种 PNG、至少四页 PPTX 下载。保留本轮 JobNo 与截图/下载 SHA。
- Modal Case 使用 `BIW_MODAL/subcase=2`，指标、图片 metadata 与报告对应同一条件；不能只测 Torsion。
- 创建请求模拟接受后响应丢失，重发原 key/body，数据库只存在一个 Job；submit 同键重复也只保留一个 attempt/PID。
- 故障 Job 的清洗日志和平台 failure code 可见；NO_RESOURCE/UNKNOWN 不误显示失败；取消申请到确认终态正确。
- 已登录 SSE 真事件触发 GET；Backend 重启导致断流，浏览器退避并恢复后 GET 当前状态，原 Job/执行继续。路由往返/退出后连接不泄漏。
- parser v2 选择展示独立指标与原 v1；reparse 不增加 Solver execution，下载保留原 SHA；图片 Object URL 生命周期正确。
- Baseline 筛选只允许同工况/Subcase，比较值来自 Backend；DRAFT 不自动显示 APPROVED，Lead Review/Admin Approve 与权限接口一致。
- 三个节点显示 ledger/observation 区别；Engineer 看不到 drain 可执行入口，直接调用返回 403；Admin drain 后新任务不会选该节点，恢复后允许调度。
- 用户看不到无权限项目的 Job、logs、artifact、report、SSE；直接输入别人的 artifact id 也拒绝。
- 401、403、409、422、500、网络超时都有真实错误提示和 traceId；空数据、loading、请求取消、日期跨午夜筛选有明确行为。
- 1440px 与窄屏页面可操作，表格允许合理横向滚动；键盘能提交登录/创建、打开链接，图表/图片不盖住主要按钮。

M9 最终门禁按第 40 节执行：format/lint、Java/Python 与已有集成测试、vue-tsc/Vite、Docker build、健康启动、上述实际浏览器验收、README 与 progress 更新、稳定提交。测试统计与验收证据只能记录本轮真实执行结果。
