# shield-x-client-java 规划 v0.1

> 状态：草案，待确认命名/发布事项后开工  
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
| 构建 | Maven 3.9+ |
| 编译目标 | Java 17（用 JDK 21 构建） |
| 默认分支 | `main` |

### 2.1 包名与 groupId 的说明

Maven **不要求** groupId 与 Java 包名相同。当前按以下组合执行：

- 发布坐标：`io.github.linyu19872008:shield-x-client-java`
- Java 包：`com.cdeer.shieldx`

这与“包名与 groupId 完全一致”的字面要求存在差异。两种修正方式：

| 方案 | 代价 |
|---|---|
| A. 包名改为 `io.github.linyu19872008.shieldx` | 与当前指示冲突；发布后改名属破坏性变更 |
| B. groupId 改为 `com.cdeer.shieldx` | 与“公开 SDK 坐标”决定冲突；gateway 依赖坐标需同步 |

**建议冻结 `com.cdeer.shieldx` 作为长期包名**，接受 groupId 与包名不同；在 `v0.1.0` 发布前不要再改。若必须严格一致，请在 M1 前拍板，之后不再迁移。

### 2.2 为什么 artifact 带 `-java`

仓库名与 artifact 都使用 `shield-x-client-java`，便于和 `shield-x-client-go` 对称识别。Maven 惯例通常不再加语言后缀，但这里按用户指定执行；`import` 时会出现 `shield-x-client-java`。

---

## 3. 边界：做什么、不做什么

| 做 | 不做 |
|---|---|
| 拉策略 / 拉模板 / 上报事件的 HTTP 客户端 | 策略求值、first-match、条件匹配 |
| 304、ETag、revision 0、空策略快照 | 模板渲染、HTML 转义、内置兜底页 |
| JSON / NDJSON / gzip、1000 条 / 1 MiB 限制 | JS 挑战、重定向、拦截执行 |
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

### 5.2 结果与配置

```java
record PoliciesResult(long revision, boolean notModified, PolicySnapshot snapshot) {}
record TemplatesResult(long revision, boolean notModified, TemplateSnapshot snapshot) {}
record ReportResult(int accepted, int rejected, Instant serverTime) {}
```

`ShieldXConfig` 使用 builder：

- `baseUrl`（必须已包含 `/shieldx` 前缀）
- `token`、`gatewayId`、`agentVersion`
- `capabilities`
- `connectTimeout`（默认 3s）、`requestTimeout`（默认 10s）
- `userAgent`
- 可选 `HttpClient`（代理、TLS、连接池由调用方决定）

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

### 5.5 编码

| 项 | 规则 |
|---|---|
| 策略 / 模板 | `GET`，`Accept: application/json` |
| 事件 | 默认 `application/x-ndjson` + gzip；可选 JSON |
| NDJSON | 第一行批次元信息，后续每行一个事件 |
| 请求头 | `Authorization`、`User-Agent`、`X-Shieldx-Agent-Version` |
| 策略请求 | 额外 `X-Shieldx-Gateway-Id`、`X-Shieldx-Capabilities` |
| 模板请求 | 额外 `X-Shieldx-Gateway-Id` |
| 批次上限 | 1000 条或 1 MiB（压缩前） |
| 压缩 | 压缩后发送，`Content-Encoding: gzip` |

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
| P4 | 空策略租户 → 200，`policies=[]` |
| P5 | JSON 批次 → 202 |
| P6 | NDJSON + gzip → 202 |
| P7 | 1001 条 → 413 `BATCH_TOO_LARGE` |
| P8 | 触发限流 → 429 + `Retry-After`（环境不满足时 Skip） |
| P9 | 跨租户隔离（需两个有策略的租户，否则 Skip） |
| P10 | 非法 body → 400 |

**不依赖 gateway**：所有测试都可以用真实 shield-x + 独立示例完成。

> 采样租户的 `accepted + rejected` 可能小于发送数（服务端对采样丢弃的事件不计数），
> SDK/reporter 不能把差额当成丢数据告警。

---

## 9. 发布

| 项 | 决策 |
|---|---|
| 版本 | `0.1.0-SNAPSHOT` → `0.1.0` |
| 契约兼容 | `agent-v1`；`0.1.x` 不保证 API 完全稳定 |
| 发布渠道 | 先 GitHub Packages；确认公开后再 Maven Central |
| 签名 | GitHub Packages 可暂不签名；Central 需要 GPG |
| License | 公开前必须确定（建议 MIT） |
| 依赖 | Jackson、SLF4J API、JUnit 5、MockWebServer；core 无 Spring |

---

## 10. 里程碑

| 阶段 | 交付物 | 验收 | 规模 |
|---|---|---|---|
| M0 | 清理占位 README/.gitignore、父 POM、`core` 模块、CI 骨架 | `mvn -B verify` 空跑通过；目录与坐标确定 | 0.5–1 天 |
| M1 | 核心 client：模型、错误、ETag/304、gzip/NDJSON、能力、事件校验、夹具测试 | 夹具 + MockWebServer 全绿；304/空策略/gzip/NDJSON 覆盖 | 3–5 天 |
| M2 | poller + reporter + spool + stats + 失败矩阵测试 | 413/429/400/5xx/队列满/spool 全部有测试 | 4–6 天 |
| M3 | 自包含 probe CI、示例、README/CHANGELOG、`v0.1.0` | 真实 shield-x + MySQL 探针通过；CI 绿 | 2–3 天 |
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

---

## 12. 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| 三套实现漂移 | Go / Java / mock 可能偏离契约 | probe + 14 向量 + 24 项验收 |
| 包名与 groupId 不一致 | `com.cdeer.shieldx` vs `io.github...` | 发布前冻结；或二选一修正 |
| Jackson 版本冲突 | gateway 的 Spring Boot BOM 可能降级 Jackson | core 只用 2.15 兼容 API；集成时验证 |
| WebFlux 阻塞 | 在 event loop 调阻塞 SDK 会拖垮网关 | SDK 独立线程；接入指南明确 |
| Maven 发布摩擦 | 私有 GitHub Packages 需 token；Central 需签名 | 先内部，后公开 |
| 模板渲染缺失 | 不做 template 模块则 gateway 自行实现 | 可选模块 + 14 向量 |
| 真实流量未验证 | probe 只覆盖协议，不覆盖网关策略 | M6 接入阶段真实验收 |

---

## 13. 待确认

| # | 事项 | 当前建议 |
|---|---|---|
| 1 | 包名是否长期保持 `com.cdeer.shieldx` | **是**，冻结，不再随 groupId 改 |
| 2 | 是否严格要求包名 = groupId | 若必须，M1 前二选一修正 |
| 3 | License | 公开前定，建议 MIT |
| 4 | Starter 是否首期做 | 二期 |
| 5 | template 模块是否首期做 | 建议做，独立 artifact |
| 6 | 发布渠道 | 先 GitHub Packages |
| 7 | Java 目标 | 17（JDK 21 构建） |
| 8 | 是否公开仓库 | 公开 SDK 建议 public |

---

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
