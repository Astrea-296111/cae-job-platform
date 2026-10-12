# 最终交付：CAE 仿真任务平台

本文件按实施规范第 56 节列出全部 17 项交付。公开版使用合成 Case、独立 Mock Solver 进程和真实平台基础设施；全部数值测量为本机 `[LOCAL-BENCH]`，不代表企业生产性能。

## 1. 最终目录树

```text
cae-job-platform/
├── backend/
│   ├── pom.xml, Dockerfile
│   └── src/
│       ├── main/java/com/example/cae/
│       │   ├── auth, job, agent, resource, scheduler, execution
│       │   ├── outbox, messaging, monitoring, result
│       │   └── report, batch, events, observability, artifact, dashboard, project, audit, common
│       ├── main/resources/db/migration/V1...V10
│       └── test/java/com/example/cae/
├── worker/
│   ├── agents, adapters, app, mock_solver, post, tests
│   └── requirements.txt, requirements-dev.txt, Dockerfile
├── frontend/
│   ├── src/{api,components,stores,views,router.ts}
│   └── package.json, package-lock.json, Dockerfile
├── deploy/
│   ├── docker-compose.yml, nginx, prometheus
│   └── grafana/{dashboards,provisioning}
├── demo-data/{structure,nvh,crash,cfd}
├── scripts/{bootstrap,check,test-backend,test-ui,demo,failure-demo,benchmark}.sh
├── tests/e2e/
├── docs/{reference,adr,design,validation}
├── Makefile
└── README.md
```

## 2. 架构摘要

Vue 经 Nginx 调用 Spring Boot 模块化单体；MySQL 是业务状态真相，Redis 是可丢失缓存，RabbitMQ 是持久命令通道。三个独立 FastAPI Agent 启动真实子进程，SQLite 保存本地请求与回调事实；共享文件系统保存不可变输入和结果。Java Parser、Python Matplotlib/python-pptx 完成结果和报告链路。Prometheus/Grafana 监测实际状态。见 [架构](architecture.md)。

## 3. 技术选型

Java 17、Spring Boot 3.3.6、Maven 3.9.9、显式 JDBC SQL/Flyway；MySQL 8.0、Redis 7、RabbitMQ 3.13。Python FastAPI、SQLite、Matplotlib 3.9.4、python-pptx 1.0.2；Vue 3.5.22、TypeScript 5.9.3、Vite 7.1.9、Pinia、Element Plus、ECharts。Nginx 1.27、Prometheus 2.54.1、Grafana 11.2.0。依赖锁与迁移已提交；ADR 说明单体、SQL、消息、Agent、共享存储和资源事务取舍。

## 4. 数据库核心表

`cae_job`/`cae_job_event`/`cae_audit_log`，`cae_job_execution`/提交与创建幂等表，node/license/reservation/profile，Outbox/Inbox/poll lease，parse record/`result_metric`/artifact，pipeline stage/report/generation request/template，batch/item/admission gate，以及 project/model/revision/membership。完整约束与迁移入口见 [数据模型](domain-model.md)。

## 5. State Machine

固定 19 个 Job 状态：CREATED、VALIDATING、READY、QUEUED、WAITING_RESOURCE、SUBMITTING、SUBMITTED、RUNNING、POST_PROCESSING、REPORTING、SUCCESS、VALIDATION_FAILED、SUBMIT_FAILED、RUNTIME_FAILED、POST_PROCESS_FAILED、REPORT_FAILED、CANCEL_REQUESTED、CANCELLED、UNKNOWN。状态和版本共同 CAS；延迟旧观测不能回退终态。UNKNOWN 保留容量并查询原执行；人审报告状态单独保存。见 [状态图](state-machine.md)。

## 6. 正常 E2E 流程

创建 → 递归 INCLUDE 校验/冻结 SHA 输入 → 提交意图/Outbox → 原子 License/CPU/内存预留 → 持久 Rabbit 命令 → Agent 请求去重/独立 PID → 回调与巡检 → 确认 Solver 终态并释放容量 → canonical Parser → 四张实际 PNG → 五页实际 PPTX → Job SUCCESS/报告 DRAFT → 人工 Review/Approve。结构扭转和模态 Subcase 2 均已完整运行。`make demo` 验证下载字节与内嵌图像，证据见 [最终演示](validation/m11-demo.json)。

## 7. 十二个故障/验收场景

| 场景 | 结果 |
| --- | --- |
| 正常结构完整报告 | PASS |
| 缺失 INCLUDE 不启动 Solver | PASS |
| 重复 submit 保持一个执行/PID | PASS |
| Agent 接受后延迟响应/原命令重放保持一个进程 | PASS |
| 运行故障保留日志并正确释放 | PASS |
| 资源不足等待/恢复自动接入 | PASS |
| 心跳消失 UNKNOWN 保留原进程/容量并恢复 | PASS |
| cancel 与完成竞态确认后释放 | PASS |
| 重复 parse 唯一记录 | PASS |
| 40 子任务部分失败批次 | PASS：37 成功、3 预期失败 |
| KPI/图像/报告工况与 Subcase 一致 | PASS |
| backend 重启 Solver 继续运行 | PASS |

全部由 `make failure-demo` 真实 Compose 运行。完整时序、返回值、资源和身份见 [M10 结果](validation/m10-failure-smoke.json) 与 [说明](validation/m10-validation.md)。延迟响应时回调可能先确认原身份，实际 Inbox retryCount 如实记录；不宣称每次都发生自动重试。

## 8. 测试统计

最终完整门禁：Java **347**，Python **60**，零失败、零跳过；Spotless/Ruff/ESLint、TypeScript/Vite、Compose 配置和应用 Docker build 通过。Java 包含真实 MySQL/Redis/Rabbit/HTTP/文件集成测试，每次使用独立 schema/namespace/vhost。真实 Chromium 验证创建、详情、报告下载、SSE、角色审核、节点管理和批次。证据见 [最终验收](validation/m11-validation.md)。

## 9. Benchmark 真实结果

1000 查询、100 并发创建、50 资源事务、100 子 Job 批次、1000 持久消息 publisher confirm、50 实际 SSE 连接，逐项记录样本和 p50/p95/p99/错误率及 MySQL CPU/连接。50 资源申请中仅 4 个获准、46 个容量拒绝，CPU/内存/License 达到上限而未超卖。完整实际数字、原始样本和测量范围见 [Benchmark](benchmark.md)；资源事务和消息发布使用独立测试 schema/vhost，不冒充端到端 API 时延。

## 10. Docker Compose 启动步骤

```bash
git clone https://github.com/Astrea-296111/cae-job-platform.git
cd cae-job-platform
docker compose -f deploy/docker-compose.yml up -d --build --wait --wait-timeout 240
# 可选：主机开发依赖及检查
bash scripts/bootstrap.sh
bash scripts/check.sh
make demo
make failure-demo
make benchmark
```

Docker/Compose 必需；主机开发/benchmark 另需 Node 20.19+（或 22.12+）、Python 3.11+，Java 17/Maven 可由 bootstrap 从官方固定镜像提取。云代理环境先执行 `scripts/configure-cloud-build.sh` 并设置 `DOCKER_CONFIG=.local/docker`；保持 TLS 校验。Flyway 自动迁移/seed，11 服务健康才验收。`make down` 保留全部卷。入口 `localhost:8088`，backend `8080`，三个 Agent `8001/8002/8003`，Prometheus `9090`，Grafana `3000`。

## 11. Demo 用户

`engineer/engineer123` 创建、提交、查询、下载；`lead/lead123` 基准选择/审核；`admin/admin123` 管理资源/批准。项目 membership 在服务端检查。Grafana 默认 `admin/demo-grafana-password`。这些只用于本地公开演示，可通过部署配置替换；前端凭证仅驻留内存。

## 12. Demo Case

结构：good、001、V36 baseline、V37 current、BIW_MODAL/Subcase 2、missing-include；NVH/CABIN_NVH、CRASH/FRONTAL_CRASH、CFD/DRAG 各一个 PILOT。合计九个公开 Case，详见 [Case 表与安全契约](case-package.md)。

## 13. Report 下载位置

UI Job 详情和报告中心有授权下载。报告详情返回实际 artifact 下载 URL；artifact 下载检查 project 权限及 SHA。服务共享卷 `jobs/{jobNo}/derived/{parserVersion}/report-v{version}/report.pptx`；演示脚本保存到 `.local/acceptance/{jobNo}/{parserVersion}/report-v{version}/report.pptx`。最终三个报告 ID、下载 URL 和 SHA 见 [演示证据](validation/m11-demo.json)。

## 14. 已实现能力

Job Domain/CAS/审计/权限、输入安全与冻结、独立四类 Mock Solver、三节点调度、持久资源账本、Outbox/Inbox/确认/重试/DLQ、身份安全 cancel、回调/巡检/UNKNOWN/restart 恢复、七项结构 canonical 指标/三个 PILOT、版本化解析/不可变下载、实际 PNG/PPTX、精确比较/显式基准/人工审核、完整 Vue 工作台/SSE/日志安全、持久 Batch/背压/公平扫描，以及实际监控与本地性能测量。M0–M11 的代码、证据和稳定提交完整保留。

## 15. 未实现 / Mock 能力

公开 Solver 及其工程结果、云图/模态形状是 Synthetic；License 是 MySQL 模拟 admission；NVH/CRASH/CFD 是 PILOT Parser。OptiStruct、HyperView、Slurm/PBS 为明确扩展协议，无商业实现。没有企业数据、真实商业算法或生产 KPI。P2 MinIO、企业 SSO、GitHub Actions 和高级 UI 不属于已实现能力。

## 16. Altair/企业服务器 Adapter 替换

替换 SolverAdapter/固定 profiles、ComputeAgentClient 与远端 Agent、HyperViewAdapter/PostProcessor、ResultParser/native formats、共享存储与 License observation；必要时接入 BatchSchedulerAdapter。保留执行身份、不可变输入/产物、工况/Subcase、幂等、确认后释放及手工审核边界。见 [实际映射](real-system-mapping.md)。

## 17. 最值得展示的五段代码

| 工程问题 | 代码入口 |
| --- | --- |
| 19 状态/版本 CAS 与原子业务事件 | `backend/src/main/java/com/example/cae/job/domain/DefaultJobTransitionPolicy.java`、`job/repository/JobRepository.java` |
| 并发资源不超卖/UNKNOWN 持有 | `backend/src/main/java/com/example/cae/resource/ResourceReservationService.java` |
| DB/MQ 双写与重复投递 | `backend/src/main/java/com/example/cae/outbox/OutboxPublisher.java`、`messaging/InboxRepository.java` |
| 重启/失联/迟到观测及身份安全恢复 | `backend/src/main/java/com/example/cae/monitoring/ExecutionMonitor.java` |
| 冻结版本/精确比较/真实报告/阶段恢复 | `backend/src/main/java/com/example/cae/report/PipelineService.java`、`worker/post/processor.py`、`worker/post/report.py` |

[面试演示顺序](interview-demo.md) 给出可运行命令与说明。
