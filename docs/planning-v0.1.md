# shield-x-client-java 规划 v0.1

> 状态：命名与坐标已确认；实现细节评审中
> 范围：只开发 `shield-x-client-java` 仓库，不改 `gateway` 代码  
> 契约：`agent-v1`  
> 参考实现：`shield-x-client-go v0.1.1`

---

## 1. 目标

为 Java 网关提供与 `shield-x-client-go` 对等的接入 SDK：

- 拉取策略快照（含 304、revision 0、空策略清空）
- 拉取拦截模板
- 批量上报请求事件（JSON / NDJSON、gzip、413/429 处理）
- 可选的 poller / reporter / spool 组件
- 可选的 Spring Boot Starter 与模板参考渲染器

**不是**网关、策略引擎或模板执行器。求值、渲染、拦截动作仍由网关负责。

---

## 2. 命名与坐标

| 项 | 决策 |
|---|---|
| 仓库 | `github.com/linyu19872008/shield-x-client-java` |
| 公开 Maven 坐标 | `io.github.linyu19872008:shield-x-client-java` |
| 开发期版本 | `0.1.0-SNAPSHOT` |
| 首个发布版本 | `0.1.0` |
| Java 包根 | `com.cdeer.shieldx` |
| 构建 | Maven 3.9+（仓库带 Maven Wrapper） |
| 编译目标 | Java 17（用 JDK 21 构建） |
| 默认分支 | `main` |

### 2.1 包名与 groupId（已确认）

- 包名 `com.cdeer.shieldx` **长期冻结**；
- Maven groupId 使用 `io.github.linyu19872008`；
- 两者故意不同，**不再迁移**；
- 公开 API 位于 `com.cdeer.shieldx` 下；`internal` 子包不承诺兼容；
- README 必须解释这一差异，避免使用者误以为坐标写错。

Maven 不要求 groupId 与 Java 包名相同；冻结包名优先于形式一致，避免公开发布后做破坏性改名。

### 2.2 模块坐标

| 模块 | 坐标 | 首期 |
|---|---|---|
| parent | `io.github.linyu19872008:shield-x-client-java-parent` | 是 |
| core | `io.github.linyu19872008:shield-x-client-java` | 是 |
| starter | `io.github.linyu19872008:shield-x-spring-boot-starter` | 二期 |
| template | `io.github.linyu19872008:shield-x-template` | 可选 |
| BOM | `io.github.linyu19872008:shield-x-client-bom` | 二期 |

### 2.3 为什么 artifact 带 `-java`

仓库名与 artifact 都使用 `shield-x-client-java`，便于和 `shield-x-client-go` 对称识别。Maven 惯例通常不再加语言后缀，但这里按用户指定执行；`import` 时会出现 `shield-x-client-java`。

---

## 3. 边界：做什么、不做什么

| 做 | 不做 |
|---|---|
| 拉策略 / 拉模板 / 上报事件的 HTTP 客户端 | 策略求值、first-match、条件匹配 |
| 304、ETag、revision 0、空策略快照 | 模板渲染、HTML 转义、内置兜底页 |
| JSON / NDJSON / gzip、1000 条 / 1 MiB 限制 | JS 挑战、网关重定向动作、拦截执行 |
| 错误信封、`Retry-After`、可重试判定 | console 管理 API、CLI、配置中心 |
| 可选 poller / reporter / spool / stats | 快照落盘格式、`fail_open` / `fail_closed` |
| 事件三态校验、能力声明 | 真实 IP 解析、脱敏、事件生成 |

---

## 4. 仓库结构

```text
shield-x-client-java/
├── pom.xml                              # parent
├── core/                                # artifact: shield-x-client-java
│   └── src/main/java/com/cdeer/shieldx/
│       ├── client/                      # ShieldXClient / config / results
│       ├── model/                       # Policy / Template / Event / Capabilities
│       ├── exception/                   # ShieldXException / TransportException
│       ├── poller/                      # 可选组件
│       ├── reporter/                    # 可选组件
│       └── spool/                       # 可选组件
├── spring-boot-starter/                 # artifact: shield-x-spring-boot-starter（二期）
├── template/                            # artifact: shield-x-template（可选）
├── examples/gateway-minimal/            # 可运行示例，不依赖 gateway
├── docs/
└── .github/workflows/
```

父 POM 只做依赖与插件管理；首期只启用 `core`，Starter 与 template 模块后加。

---

## 5. 核心 API

```java
ShieldXClient client = ShieldXClient.builder()
    .baseUrl("http://shield-x.internal:8080/shieldx")
    .token("sx_...")
    .gatewayId("gw-prod-01")
    .agentVersion("gateway/2.1.1")
    .capabilities(Capabilities.builder()
        .actions("observe", "block")
        .matchFields("ip", "host", "path")
        .blockTemplates(true)
        .templateSpecVersion(1)
        .maxRules(5000)
        .build())
    .build();

PoliciesResult result = client.fetchPolicies(-1);
if (!result.notModified()) {
    state.replace(result.snapshot());
}
```

### 5.1 公开方法

```java
PoliciesResult  fetchPolicies(long sinceRevision)
TemplatesResult fetchTemplates(long sinceRevision)
ReportResult    reportEvents(EventBatch batch)
```

- `sinceRevision = -1`：不发送 `If-None-Match`（无本地快照）
- `sinceRevision >= 0`：发送 `If-None-Match: "rev-<n>"`，revision 0 合法
- 304 不是异常：`notModified=true`，`snapshot=null`
- 失败不覆盖调用方旧快照；poller 负责保留

Revision 解析规则（与 client-go 对齐）：

| 响应 | 规则 |
|---|---|
| 200 | 以 body 的 `revision` 为准；缺失按 0 处理并告警（与 client-go 一致） |
| 304 | 解析 `ETag: "rev-N"`；缺失或非法时回退到请求的 `sinceRevision` |
| revision 0 | 合法；不能被当成“没有 revision” |
| ETag 非法 | 不抛解析异常；回退并记录 |

### 5.2 结果与配置

```java
record PoliciesResult(long revision, boolean notModified, PolicySnapshot snapshot) {}
record TemplatesResult(long revision, boolean notModified, TemplateSnapshot snapshot) {}
record ReportResult(int accepted, int rejected, Instant serverTime) {}
```

`ShieldXConfig` 使用 builder，构造后不可变：

| 配置 | 规则 |
|---|---|
| `baseUrl` | 必须是 http/https 绝对地址，包含 `/shieldx` 前缀；不允许 userinfo/query/fragment |
| `token` | 二选一：静态字符串，或 `Supplier<String>`（支持轮换，每次请求取值） |
| `gatewayId` | 非空；写入 `X-Shieldx-Gateway-Id` |
| `agentVersion` | 非空；写入 `X-Shieldx-Agent-Version` 与批次元信息 |
| `capabilities` | 必填；序列化后写入 `X-Shieldx-Capabilities` |
| `connectTimeout` | 默认 3s |
| `requestTimeout` | 默认 10s |
| `maxResponseBytes` | 默认 32 MiB；解压后上限 |
| `userAgent` | 默认 `shield-x-client-java/<version>` |
| `httpClient` | 可选；代理、TLS、连接池由调用方决定，SDK 不关闭 |

- 配置校验失败在 builder 阶段抛 `IllegalArgumentException`，不等到第一次请求。
- 不支持运行时热更新；token 轮换用 `Supplier<String>`，其余变更重建 client。

### 5.3 异常

```java
class ShieldXException extends RuntimeException {
    int statusCode();
    String code();
    String requestId();
    Duration retryAfter();
    boolean retryable();       // 429 / 5xx
    boolean authentication();  // 401 / 403
}
```

- Token 不进入异常消息、日志或 `toString()`
- 网络错误包装为 `TransportException`，保留 cause
- `InterruptedException` 恢复中断标志，不吞掉

### 5.4 模型

- 使用 Java `record`；DTO 不用 `Optional` 字段
- `match`、`action.params` 用 Jackson `JsonNode` 保留未知字段
- 动作、决策来源、事件类型用 **String + 常量类**，不硬编码 enum
- `matchedPolicyId` 未命中时序列化为 `null`，不能全类 `NON_NULL`
- 时间用 `Instant` + `JavaTimeModule`，按 RFC 3339 输出
- `event_id` 必填、1–64 字符、网关内唯一；重试/补发时不得重新生成

### 5.5 编码

| 项 | 规则 |
|---|---|
| 策略 / 模板 | `GET`，`Accept: application/json` |
| 事件 | 默认 `application/x-ndjson` + gzip；可选 JSON |
| NDJSON | 第一行批次元信息，后续每行一个事件 |
| 批次元信息 | `agent_version`、`gateway_id`、`config_revision`、`policy_count`、可选 `client_stats` |
| JSON 格式 | 同一份元信息 + `events` 数组 |
| 请求头 | `Authorization`、`User-Agent`、`X-Shieldx-Agent-Version` |
| 策略请求 | 额外 `X-Shieldx-Gateway-Id`、`X-Shieldx-Capabilities` |
| 模板请求 | 额外 `X-Shieldx-Gateway-Id` |
| 批次上限 | 1000 条或 1 MiB（压缩前） |
| 压缩 | 压缩后发送，`Content-Encoding: gzip` |

### 5.6 HTTP 传输策略

| 项 | 约定 |
|---|---|
| 客户端 | 默认 JDK `HttpClient`；可通过 builder 注入自定义实例（代理/TLS/连接池） |
| 所有权 | SDK 不关闭调用方注入的 `HttpClient`；未注入时也不提供客户端级 `close()` |
| 重定向 | **禁止自动跟随**（`Redirect.NEVER`）；避免 `Authorization` 被带到其他 origin |
| TLS | 默认信任库 + 主机名校验；允许注入自定义 `SSLContext`；**不提供跳过校验的开关** |
| 代理 | 由注入的 `HttpClient` 配置；代理认证由调用方负责，SDK 不记录凭据 |
| 超时 | connect 默认 3s、request 默认 10s，均可配置；poller/reporter 使用同一配置 |
| 协议 | HTTP/1.1 与 HTTP/2 由 JDK 协商；默认 keep-alive |
| 请求压缩 | 事件默认 gzip；策略/模板请求不压缩 |
| 响应压缩 | 支持 `Content-Encoding: gzip`；解压后仍受响应上限约束 |
| 响应上限 | 默认 32 MiB（解压后）；超过直接失败，避免 OOM |
| 错误体 | 最多读取 64 KiB 用于解析错误信封；日志只记录截断后的 message |
| 自动重试 | 传输层不自动重试；重试只由 poller/reporter 决策 |
| User-Agent | `shield-x-client-java/<version>`，可由配置覆盖 |

`baseUrl` 校验：

- 必须是 `http` 或 `https` 绝对地址；
- 不允许 userinfo、query、fragment；
- 必须包含 `/shieldx` 路径前缀；末尾 `/` 会被规范化；
- 不允许通过重定向改变 origin。

### 5.7 交付语义

- **上报是 at-least-once**：202 表示服务端已接收；超时、5xx、429、spool 补发都可能让同一批事件被服务端看到多次。
- **SDK 不保证 exactly-once**，也不假设服务端去重；`event_id` 必须由网关生成，并在重试/补发时保持不变。
- **队列满丢最旧**：这是有意的背压策略，属于数据丢失；必须通过 `dropped_total` 和告警暴露。
- **400 丢弃**：服务端明确拒绝的批次不重试；记录 `server_rejected` 和样本原因。
- **413 拆半**：拆分后的子批可能部分成功、部分失败；整批不保证原子性。
- **关闭**：`close(timeout)` 只保证在超时内尽力 flush；超时后剩余事件按 spool/队列策略处理。
- **采样**：采样租户 `accepted + rejected` 可能小于发送数；不能把差额当成 SDK 丢数据。
- **poller**：快照整体替换；失败保留旧快照；304 不改变 revision。

### 5.8 安全与隐私

| 项 | 约定 |
|---|---|
| Token | 只放 `Authorization`；不写日志、不进异常、不出现在 `toString()` |
| 重定向 | 禁止跟随，避免跨域泄漏 Token |
| TLS | 默认校验证书与主机名；不允许“跳过校验”配置 |
| 日志 | 默认不打印事件体、Header、Token；只记录 gatewayId/revision/status/requestId/batchSize |
| Spool | 目录 0700、文件 0600；内容可能含 IP/Header，按敏感数据管理 |
| 保留 | Spool 按文件数/字节淘汰；不自动加密，部署方负责磁盘加密 |
| 代理 | 代理凭据由调用方配置，SDK 不记录 |
| 错误体 | 仅在 DEBUG 记录截断后的错误 message；避免把用户数据写入日志 |


---

## 6. 可选组件

### 6.1 Poller

```java
try (Poller poller = Poller.builder(client)
        .interval(Duration.ofSeconds(5))
        .initialRevision(savedRevision)
        .onSnapshot(state::replace)
        .onError(log::warn)
        .build()) {
    poller.start();
}
```

- 单线程 `ScheduledExecutorService`，守护线程，立即首拉
- 304、空策略清空、失败保留旧快照
- 退避 1s → 60s，±20% jitter；`Retry-After` 优先
- `AutoCloseable`，关闭时取消并等待
- 模板只提供 `refreshTemplates()`，不自动猜缺失模板

### 6.2 Reporter

- 有界 `ArrayBlockingQueue`，`enqueue` 非阻塞；满时丢最旧并计数
- 单线程 dispatcher，按条数 / 时间切批
- 413 拆半、429 退避、400 丢弃、401/403 退避、5xx/网络 spool
- `stateSupplier` 提供 revision / policyCount
- `close(Duration)` 做最后一次 flush
- Stats：accepted / rejected / dropped / sent / spooled / replayed / queue / lastError

### 6.3 Spool

- 文件名：`v1-<epochMillis>-<seq>-<events>.json.gz`
- 临时文件 + `ATOMIC_MOVE`
- 按文件数 / 字节淘汰
- 启动与恢复后补发；损坏文件跳过并记录
- 不要求和 Go 的 spool 字节兼容，但保持相同命名与结构习惯

### 6.4 线程、生命周期与关闭

| 项 | 约定 |
|---|---|
| Poller | 单线程 `ScheduledExecutorService`，线程名 `shieldx-poller`，daemon |
| Reporter | 单线程 dispatcher，线程名 `shieldx-reporter`，daemon |
| 回调 | `onSnapshot` / `onError` 在对应组件线程执行；必须快速、非阻塞；异常被捕获并记录，不能杀死循环 |
| Client | 不可变、线程安全；可在 poller 与 reporter 间共享 |
| 状态 | 快照由调用方持有（`AtomicReference`/volatile）；SDK 不持久化 |
| 关闭顺序 | 先停止接收新事件 → reporter flush → poller 停止；各组件 `close` 幂等 |
| 关闭超时 | `close(Duration)` 超时后强制停止；未完成事件按 spool/队列策略处理 |
| 中断 | `InterruptedException` 恢复中断标志并尽快退出 |
| Executor | 组件自建并负责关闭；首期不暴露 executor 注入，避免所有权不清 |
| 多实例 | 每个网关进程一个 poller/reporter；Spool 目录必须独占，禁止多 JVM 共用 |


### 6.5 性能与资源基线

首期不承诺硬 SLA，但固定可测基线，避免实现时拍脑袋：

| 项 | 基线 |
|---|---|
| 默认队列 | 10000 事件 |
| 默认批大小 | 300（契约上限 1000 / 1 MiB） |
| 线程 | poller 1 + reporter 1，daemon |
| 内存 | 队列 + 单批编码缓冲 + 单次响应上限 32 MiB |
| enqueue | 非阻塞；满时丢最旧并计数 |
| 启动 | 不因网络阻塞；poller 首拉异步 |
| 关闭 | 默认 flush timeout 5s，可配置 |
| benchmark | enqueue 吞吐、300/1000 条编码耗时、spool replay 吞吐；JMH 后续补，不阻塞 v0.1.0 |


### 6.6 可观测性

core 暴露不可变 `Stats` 快照，不强制 Micrometer；Starter 负责转成指标。

| 指标 | 说明 |
|---|---|
| `shieldx.poll.total` | 标签 `result`：ok / not_modified / error |
| `shieldx.poll.last_success_timestamp` | 最近成功时间 |
| `shieldx.report.events.total` | 标签 `result`：accepted / rejected / dropped / spooled / replayed |
| `shieldx.report.batches.total` | 标签 `result`：ok / split / retry / drop |
| `shieldx.report.queue.depth` | 当前队列深度 |
| `shieldx.report.spool.events` / `.bytes` | Spool 规模 |
| `shieldx.report.dropped.total` | 队列满丢弃总数 |

日志字段固定为：`gateway_id`、`revision`、`status`、`request_id`、`batch_size`、`retry_after`、`result`。
不记录 Token、事件体、原始 Header；高基数标签禁止使用 `gateway_id`/`request_id`。

Health Indicator：

- reporter/poller 线程存活、Spool 目录可写；
- 最近一次成功轮询超过 3 个间隔时在 details 标记 `stale=true`，但不直接把 readiness 置 DOWN；
- 网络抖动不应导致网关被摘除。


---

## 7. 可选模块

### 7.1 Spring Boot Starter（二期）

配置前缀 `shield-x`：

```yaml
shield-x:
  enabled: true
  base-url: http://shield-x.internal:8080/shieldx
  token: ${SHIELD_X_TOKEN}
  gateway-id: gw-prod-01
  agent-version: gateway/2.1.1
  poll:
    interval: 5s
  report:
    batch-size: 300
    spool-dir: ./data/spool
```

提供：

- `@ConfigurationProperties("shield-x")`
- `ShieldXClient` / `Poller` / `Reporter` Bean
- `SmartLifecycle` 启停
- Actuator Health Indicator
- Micrometer 指标

core **不依赖 Spring**；非 Spring 网关可以只用 core。

### 7.2 Template 参考渲染器（可选）

独立 artifact `shield-x-template`，不混入 core：

- 实现契约 §9 的占位符替换、HTML 转义、未知占位符处理
- 跑满 14 条 `template-conformance` 向量
- 明确标注“可选参考实现”；gateway 可依赖，也可自己实现

策略求值不提供。

---

## 8. 测试与验收

| 层 | 内容 |
|---|---|
| 单元测试 | JUnit 5 + MockWebServer / AssertJ；304、空策略、401/403/413/429/500、gzip、NDJSON、批次上限、错误信封、超时 |
| 契约夹具 | 复用/复制 `shield-x-client-go/testdata/contract/*.json` |
| Poller | 立即首拉、304、空清空、失败保留、退避、关闭 |
| Reporter | 切批、413 拆半、429 退避、400 丢弃、队列满、spool 补发、优雅退出 |
| 契约探针 | 用 `java.net.http` 裸写 10 条断言，**不 import SDK**；CI 启动真实 shield-x + MySQL |
| 渲染 | 若启用 template 模块，14/14 向量 |
| 质量门禁 | `mvn -B verify`、Spotless、JaCoCo、Java 17/21 matrix |

契约探针覆盖：

| 用例 | 断言 |
|---|---|
| P1 | 无 Token → 401 |
| P2 | 拉策略 → 200，顺序保持，ETag 存在 |
| P3 | `If-None-Match` → 304，空 body |
| P4 | 空策略租户 C → 200，`policies=[]` |
| P5 | JSON 批次 → 202 |
| P6 | NDJSON + gzip → 202 |
| P7 | 1001 条 → 413 `BATCH_TOO_LARGE` |
| P8 | 触发限流 → 429 + `Retry-After`（环境不满足时 Skip） |
| P9 | 跨租户隔离（A/B 各至少一条不同策略，否则 Skip） |
| P10 | 非法 body → 400 |

**不依赖 gateway**：所有测试都可以用真实 shield-x + 独立示例完成。

> 采样租户的 `accepted + rejected` 可能小于发送数（服务端对采样丢弃的事件不计数），
> SDK/reporter 不能把差额当成丢数据告警。

### 8.1 CI 流水线

| Job | 内容 |
|---|---|
| unit | JDK 17 + 21 matrix；`mvn -B verify`；Spotless；JaCoCo |
| contract-probe | JDK 17；MySQL service；Go 环境；checkout shield-x **固定 tag/commit**；启动真实 shield-x；跑 Java 裸 HTTP probe |
| security | CodeQL（Java）、Dependabot（Maven + Actions） |
| release | tag 触发；sources/javadoc/GPG；GitHub Packages；后续 Maven Central |

- unit 在每个 PR 跑；probe 在 nightly 与 release 前手动跑，避免 MySQL/Go 环境拖慢 PR。
- probe 通过 read-only deploy key 读取 shield-x，不使用宽权限 PAT。
- CI **不依赖 gateway**。
- 覆盖率目标：core 80% 行覆盖，reporter 70%；不达标先补测试，不靠排除规则。

### 8.2 Probe 版本固定

- `SHIELDX_REF` 固定到已通过 `agent-v1` 验收的 tag/commit，**不使用 `main`**。
- 启动前校验 `CONTRACT_VERSION=agent-v1`。
- 三个租户：A/B 各至少一条策略且互不重叠（P2/P9）；C 为空策略（P4）。
- P8 只有在服务端可触发限流时执行，否则显式 Skip 并记录原因。
- Probe 使用 `java.net.http` 裸写，不 import SDK，避免共享错误假设。
- 可选：对 Go `mock-gateway` 跑同一组 probe，验证独立实现；不作为 v0.1.0 门禁。

### 8.3 测试矩阵

| 类别 | 用例 |
|---|---|
| 304/ETag | 正常 304、304 缺 ETag、非法 ETag、revision 0 |
| 策略 | 空策略、5000 条、未知字段、重复 id、`matchedPolicyId=null` |
| 编码 | JSON、NDJSON、gzip、空行、非法 JSON、超大响应、gzip 解压上限 |
| 错误 | 400/401/403/404/408/413/429/500/503、`Retry-After` 秒/HTTP-date、错误体非法 |
| 传输 | 超时、连接拒绝、DNS 失败、TLS 失败、重定向（必须不跟随）、代理 |
| Reporter | 切批、413 拆到 1 条、429、400、5xx、队列满、spool 补发、关闭超时 |
| 并发 | 多线程 enqueue、enqueue 与 close 竞争、回调抛异常 |
| Spool | 损坏文件、磁盘满、权限不足、原子 rename、多 JVM 共用（应拒绝或明确不支持） |
| 时间 | fake clock 退避、jitter 范围、关闭等待 |

### 8.4 跨语言夹具治理

- 当前以 `shield-x-client-go/testdata/contract/*.json` 为参考，复制到 Java `src/test/resources/contract/`。
- 记录 `contract-fixtures.sha256` 与来源 commit，便于审计。
- 契约变更时两侧同时更新夹具，probe 与单测一起过。
- 长期可把 canonical fixtures 移到 shield-x 仓库统一维护；首期不做，避免跨仓阻塞。


---

## 9. 发布与工程

### 9.1 Maven 发布

| 项 | 决策 |
|---|---|
| 版本 | `0.1.0-SNAPSHOT` → `0.1.0` |
| 契约兼容 | `agent-v1`；`0.1.x` 不保证 API 完全稳定 |
| 构建 | Maven Wrapper（`mvnw`），Maven 3.9+ |
| 编译 | `maven.compiler.release=17`；CI 用 JDK 17 + 21 |
| 产物 | core、starter、template、BOM；sources/javadoc jar |
| 发布渠道 | 先 GitHub Packages（snapshot）；稳定后 Maven Central |
| 签名 | Central 需要 GPG；GitHub Packages 可暂不签名 |
| 可复现 | 设置 `project.build.outputTimestamp` |
| 模块名 | `Automatic-Module-Name: com.cdeer.shieldx.client` |
| Enforcer | 要求 Maven 3.9+、Java 17+；禁止依赖收敛冲突 |
| API 兼容 | `0.1.0` 后引入 japicmp/Revapi，只检查公开 API；`internal` 不承诺 |
| License | 公开前必须确定；建议 MIT |

依赖策略：

- core 只依赖 Jackson（databind + jsr310）与 SLF4J API；不依赖 Spring / Fastjson / Lombok。
- Jackson 只使用 2.15 兼容 API；CI 额外验证 Spring Boot 3.2.10 的 Jackson BOM。
- 用 `maven-dependency-plugin:analyze` 检查未声明/未使用依赖。
- starter 才引入 Spring Boot；template 不依赖 core。

发布顺序：

1. `core` + parent + BOM；
2. `starter`；
3. `template`；
4. 更新 README 兼容矩阵与 CHANGELOG。

### 9.2 公共仓库治理

公开 SDK 需要补齐：

| 文件/设置 | 内容 |
|---|---|
| `LICENSE` | MIT（待确认） |
| `SECURITY.md` | 漏洞上报方式与响应时限 |
| `CONTRIBUTING.md` | 开发、测试、提交规范 |
| `CODE_OF_CONDUCT.md` | 社区行为准则 |
| `CHANGELOG.md` | Keep a Changelog 风格 |
| Issue/PR 模板 | bug、feature、contract 变更 |
| Dependabot | Maven + GitHub Actions |
| CodeQL | Java 静态扫描 |
| 分支保护 | `main` 必需 unit CI；禁止直推 |
| 标签 | 发布 tag 与 CHANGELOG 对应；建议签名 tag |
| Maven Central | 验证 `io.github.linyu19872008` 命名空间归属 |

公开仓库中不得出现 Token、私钥、真实租户数据；probe 的 deploy key 只放 Actions Secrets。

---

## 10. 里程碑

| 阶段 | 交付物 | 验收 | 规模 |
|---|---|---|---|
| M0 | 清理占位、删除 `master`、Java/Maven `.gitignore`、Maven Wrapper、parent POM、`core` 模块、CI 骨架、LICENSE/SECURITY 初稿（License 待确认） | `mvn -B verify` 空跑通过；目录与坐标确定；治理文件骨架就位 | 0.5–1 天 |
| M1 | 核心 client：模型、错误、ETag/304、gzip/NDJSON、能力、事件校验、HTTP 传输策略、夹具测试 | 夹具 + MockWebServer 全绿；304/空策略/gzip/NDJSON/重定向/超时覆盖 | 3–5 天 |
| M2 | poller + reporter + spool + stats + 线程/关闭语义 + 失败矩阵测试 | 413/429/400/5xx/队列满/spool/关闭超时全部有测试 | 4–6 天 |
| M3 | 自包含 probe CI（固定 shield-x tag）、示例、README/CHANGELOG、`v0.1.0` | 真实 shield-x + MySQL 探针通过；CI 绿；`v0.1.0` 可发布 | 2–3 天 |
| M4 | Spring Boot Starter | 上下文启动、配置绑定、启停、Health 测试 | 2–3 天 |
| M5 | `shield-x-template` + 14 向量 | 14/14 通过 | 1–2 天 |
| M6 | gateway 接入指南 | 文档评审通过；**不改 gateway 代码** | 0.5–1 天 |

总计约 **2.5–3.5 周**。如果只有一周：完成 M0–M2 的最小闭环 + M3 的 probe，暂不做 Starter/template。

---

## 11. gateway 接入（后续，不在本计划实现）

1. gateway 引入 `io.github.linyu19872008:shield-x-client-java`；
2. 配置 base-url/token/gateway-id/agent-version/capabilities；
3. 启动 poller，把快照交给现有策略引擎；
4. 请求路径按策略执行，构造 `Event`，`reporter.enqueue`；
5. 跑 24 项验收 + 14 条渲染向量；
6. 观察 304 比例、队列深度、spool、丢弃数。

注意：gateway 是 WebFlux 响应式网关，SDK 的 poller/reporter 必须使用独立线程，不能在 event loop 上阻塞。

本计划的硬约束：

- SDK 不 import Spring、WebFlux 或 gateway 的任何类型；
- 示例应用是独立的 Spring Boot 应用，不复制 gateway 代码；
- M6 只交付接入指南与配置示例，**不在本仓库修改 gateway**；
- SDK 的验收不依赖 gateway，真实流量验收留到后续接入阶段。

---

## 12. 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| 三套实现漂移 | Go / Java / mock 可能偏离契约 | probe + 14 向量 + 24 项验收 |
| 重定向泄漏 Token | 跟随 3xx 可能把 Authorization 带到其他 origin | `Redirect.NEVER`；不提供开启开关 |
| 重复事件 | 重试与 spool 补发是 at-least-once | 文档明确；`event_id` 由网关稳定生成；监控重复率 |
| 队列满/关闭丢数据 | 丢最旧与 flush 超时会丢事件 | `dropped_total`、关闭超时指标、告警 |
| Spool 泄露 PII | 事件可能含 IP/Header | 0700/0600、保留策略、磁盘加密由部署方负责 |
| Jackson 版本冲突 | gateway 的 Spring Boot BOM 可能降级 Jackson | core 只用 2.15 兼容 API；CI 用 Boot 3.2 BOM 验证 |
| WebFlux 阻塞 | 在 event loop 调阻塞 SDK 会拖垮网关 | 独立线程；接入指南明确 |
| 公开仓库治理缺失 | 无 LICENSE/SECURITY/CI 会影响发布 | M0 补齐 |
| Probe 使用 main | 服务端变动导致 Java CI 无故红 | 固定 shield-x tag/commit；contract version 校验 |
| Maven 发布摩擦 | Central 需签名、命名空间验证 | 先 GitHub Packages，后 Central |
| 模板渲染缺失 | 不做 template 模块则 gateway 自行实现 | 可选模块 + 14 向量 |
| 真实流量未验证 | probe 只覆盖协议，不覆盖网关策略 | M6 接入阶段真实验收 |
| 401/403 重试风暴 | Token 失效时 reporter 持续退避重试 | 指数退避 + 告警；Token 轮换用 Supplier |
| 命名差异困惑 | groupId 与包名不同，使用者可能以为坐标写错 | README 解释“故意不同、已冻结” |

---

## 13. 待确认

| # | 事项 | 当前状态 |
|---|---|---|
| 1 | 发布渠道 | 先 GitHub Packages；Maven Central 待定 |
| 2 | 仓库可见性 | 公开 SDK 建议 public；当前仍为 private |
| 3 | at-least-once + 重复事件 | 需与 gateway/服务端确认可接受 |
| 4 | Maven Central 命名空间 | 发布前验证 `io.github.linyu19872008` 归属 |
| 5 | P8/P9 的 CI 环境 | P8 需限流；P9 已通过种子策略执行 |

已关闭：包名/坐标、Java 17、License（MIT）、Starter、template、probe 版本固定。

## 附录 A：接口与请求头

| 用途 | 方法与路径 |
|---|---|
| 拉策略 | `GET /policies/v1` |
| 拉模板 | `GET /templates/v1` |
| 上报事件 | `POST /events/v1` |

| 请求头 | 说明 |
|---|---|
| `Authorization: Bearer <token>` | 租户 Token |
| `X-Shieldx-Gateway-Id` | 网关 ID |
| `X-Shieldx-Agent-Version` | 网关版本 |
| `X-Shieldx-Capabilities` | 能力 JSON（策略请求） |
| `If-None-Match: "rev-N"` | 有本地快照时 |
| `Content-Encoding: gzip` | 压缩事件时 |
| `Content-Type: application/x-ndjson` | 默认事件格式 |

## 附录 B：Reporter 失败矩阵

| 响应 | 行为 |
|---|---|
| 202 | 清空该批，累加 accepted/rejected |
| 413 | 拆半重发，不原样重试 |
| 429 | 按 `Retry-After` 退避；缺失则指数退避 |
| 400 | 丢弃该批，不重试 |
| 401/403 | 告警 + 退避重试，不丢数据 |
| 5xx / 超时 / 网络 | 有 spool 写 spool；无 spool 回队列 |
| 队列满 | 丢最旧，`dropped_total++` |

## 附录 C：兼容性矩阵

| client-java | 契约 | shield-x | Java | Spring Boot（starter） |
|---|---|---|---|---|
| `0.1.x` | `agent-v1` | `0.9.x` | 17 / 21 | 3.2.x |
| `0.2.x` | `agent-v1` | `0.9.x` | 17 / 21 | 3.2.x+ |

补充：

- core 编译目标为 Java 17；Java 21 可直接消费。
- Jackson 只使用 2.15 兼容 API；CI 验证 Spring Boot 3.2.10 管理的版本。
- `0.1.x` 为初始版本，minor 升级可能包含破坏性调整；升级前看 CHANGELOG。

## 附录 D：发布检查清单

- [ ] `pom.xml` 版本从 `-SNAPSHOT` 改为正式版
- [ ] CHANGELOG 写好 Added / Changed / Fixed / Breaking
- [ ] LICENSE、SECURITY、CONTRIBUTING、CODE_OF_CONDUCT 就位
- [ ] JDK 17 与 21 的 `mvn -B verify` 全绿
- [ ] contract probe 固定 shield-x tag/commit 并通过
- [ ] sources/javadoc jar 可生成
- [ ] GPG 签名（Central）
- [ ] Maven Central 命名空间已验证
- [ ] tag 与版本一致，推送 tag
- [ ] GitHub Release 说明与 CHANGELOG 一致
- [ ] 发布后从干净项目 `mvn dependency:get` 冒烟
- [ ] README 兼容矩阵更新

## 附录 E：错误/重试完整矩阵

| 场景 | 同步 client | Poller | Reporter |
|---|---|---|---|
| 200 / 202 | 正常返回 | 更新/保留快照 | 清空批次，累计 accepted/rejected |
| 304 | `notModified=true` | 保留快照与 revision | N/A |
| 3xx | `ShieldXException`（不跟随） | 退避 + 告警 | 退避 + 告警，不丢数据 |
| 400 | `ShieldXException` | 退避 + 告警（配置/契约问题） | 丢弃该批，`server_rejected++` |
| 401 / 403 | `authentication=true` | 退避 + 告警 | 退避 + 告警，不丢数据 |
| 404 | `ShieldXException` | 退避 + 告警（baseUrl 配错） | 退避 + 告警 |
| 408 / 5xx | `retryable=true` | 指数退避 | 写 spool；无 spool 回队列 |
| 413 | `ShieldXException` | N/A | 拆半重发；单条仍 413 则丢弃并告警 |
| 429 | `retryAfter` 优先，否则指数退避 | 同样 | 同样，保留批次 |
| 超时 / 网络 / DNS / TLS | `TransportException` | 退避 | 写 spool；无 spool 回队列 |
| 响应 JSON 非法 | 协议错误 | 保留旧快照，退避 | 重试并告警，避免静默丢数据 |
| 队列满 | N/A | N/A | 丢最旧，`dropped_total++` |
| 关闭超时 | N/A | 强制停止 | 剩余按 spool/队列处理 |
