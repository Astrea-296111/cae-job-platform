# [LOCAL-BENCH] 实际测量

测量完成：2026-10-12 17:22:33 Asia/Shanghai。单台云环境，cgroup CPU 配额 4 核、内存上限 32 GiB，Docker vfs，MySQL/Redis/RabbitMQ、Java backend、三个 public Worker 共用资源。数据包含 HTTP Basic 认证、业务处理、连接池等待及网络传输，不代表公司生产 KPI，也不能外推分布式集群吞吐。

| 工作负载 | 样本 | p50 ms | p95 ms | p99 ms | 错误率 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Job GET，20 并发 | 1000 | 554.680 | 951.373 | 1151.222 | 0.0% |
| Job 创建，100 并发 | 100 | 2493.667 | 2918.793 | 2990.558 | 0.0% |
| 资源事务，50 并发 | 50 | 41.079 | 57.450 | 62.488 | 0.0% |
| 100-child Batch API 接受（仅 1 样本） | 1 | 165.782 | 165.782 | 165.782 | 0.0% |
| Batch 子任务持久接入等待 | 100 | 36356.621 | 73080.291 | 76398.537 | 0.0% |
| Outbox 持久发布/confirm/落库 | 1000 | 7.683 | 12.082 | 15.555 | 0.0% |
| SSE 首个 ready，50 并发 | 50 | 952.896 | 1353.432 | 1421.695 | 0.0% |

100 次并发创建产生 100 个不同 Job；原键重放返回原 Job。创建不隐式启动 Solver。50 个实际资源事务仅获准 4 个、容量拒绝 46 个；峰值 CPU 16/16、内存 32/32 GiB、OPTISTRUCT Token 4/4。容量拒绝是正确业务结果，单独记录，不冒充成功执行或系统错误。

100 子任务批次 `BATCH-9a22998d-b897-49d1-9ea1-5f635f89fb0f` 全部 SUCCESS，实际端到端完成 98.583 秒。API 接受仅测量一次，三个分位数相同；子任务接入等待的 100 个样本包含背压停顿，不能当作 100 次批次 API 时延。1000 条消息经持久 publisher confirm 后数据库全部 SENT。50 个 SSE 连接实际收到 ready，live Gauge 增加 50；客户端关闭并触发本次新建 Job 事件后，Gauge 从 50 → 27 → 1 → 0，连接全部释放。

MySQL 容器观测 28 次，无采集错误；Docker CPU 平均 20.37%、峰值 43.11%（Docker 100% 代表一个 CPU 核）；Threads_connected 17–17，Threads_running 2–3。原始容器内存与全局 Max_used_connections 一并保存；后者含本部署此前活动，不能当作本次基准的连接峰值。

Java 资源和 Outbox driver 在 harness 独立 schema/vhost 中使用真实事务和 Rabbit confirm。Outbox 逐条发布，样本计时包含 confirm 和 SENT 记录；它不是生产 QPS 测试。HTTP/SSE/Batch 使用运行中的公开 Compose 部署。延迟分位数使用排序后 ceil(n×p) 的最近秩，无预热假设；所有原始样本可检查。脚本不得清除应用数据或队列。

复现：先 `make up` 与 `bash scripts/bootstrap.sh`，再 `make benchmark`。本次曾因测试客户端对无界流的连接关闭方式修正并重跑 HTTP 部分；当前代码显式关闭 socket，且以真实事件和 Gauge 验证断开。Java driver 独立实测与最终 HTTP 样本分别保留。

证据：[HTTP/Batch/SSE 原始结果](validation/m11-benchmark.json)、[Java 资源/Outbox 原始样本](validation/m11-benchmark-java.json)。
