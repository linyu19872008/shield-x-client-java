# shield-x-client-java 公开 API

> 契约：`agent-v1` · Maven：`io.github.linyu19872008:shield-x-client-java` · 状态：`0.1.x` 预览
>
> [English](../client-java-api-v1.md) | **简体中文**

## 1. 范围

SDK 只实现网关侧协议：拉策略/模板、上报事件、HTTP 编码、重试、批量与 spool。
它**不**负责策略求值、模板渲染或拦截执行。

包根：`com.cdeer.shieldx`。

## 2. 模块

| 模块 | 说明 |
|---|---|
| `core` | client、模型、poller、reporter、spool |
| `spring-boot-starter` | Spring Boot 集成 |
| `template` | 可选参考渲染器 |
| `examples/gateway-minimal` | 最小接线示例 |

## 3. 客户端配置

```java
ShieldXClient client = ShieldXClient.builder()
    .baseUrl("http://shield-x.internal:8080/shieldx")
    .token("sx_...")
    .gatewayId("gw-prod-01")
    .agentVersion("gateway/2.1.1")
    .capabilities(Capabilities.builder()
        .actions("observe", "block")
        .matchFields("ip", "host", "path")
        .maxRules(5000)
        .build())
    .build();
```

| 配置 | 规则 |
|---|---|
| `baseUrl` | 绝对 http(s) 地址，包含 `/shieldx`；不允许 userinfo/query/fragment |
| `token` | 静态字符串或 `Supplier<String>`；不写日志 |
| `gatewayId` | 1–64 个 `[A-Za-z0-9._-]` 字符 |
| `agentVersion` | 非空网关版本 |
| `capabilities` | 可选，使用前校验 |
| `connectTimeout` | 默认 3s |
| `requestTimeout` | 默认 10s |
| `maxResponseBytes` | 默认 32 MiB（解压后） |
| `httpClient` | 可选注入；SDK 不关闭 |

配置在 `build()` 后不可变；非法配置立即抛 `IllegalArgumentException`。

## 4. 客户端方法

```java
PoliciesResult  fetchPolicies(long sinceRevision)
TemplatesResult fetchTemplates(long sinceRevision)
ReportResult    reportEvents(EventBatch batch)
ReportResult    reportEvents(EventBatch batch, ReportOptions options)
```

- `sinceRevision = -1`：不带 `If-None-Match`。
- `sinceRevision >= 0`：发送 `If-None-Match: "rev-N"`，revision 0 合法。
- 304：`notModified=true`、`snapshot=null`，不是异常。
- 200：解码快照；以 body 的 `revision` 为准。
- 304 无有效 ETag：回退到 `sinceRevision`。
- 错误：`ShieldXException`，包含 status/code/requestId/retryAfter。

`ReportOptions` 默认 NDJSON + gzip；可用 `ReportOptions.json()` 或 builder 修改。

## 5. 结果

```java
record PoliciesResult(long revision, boolean notModified, PolicySnapshot snapshot) {}
record TemplatesResult(long revision, boolean notModified, TemplateSnapshot snapshot) {}
record ReportResult(int accepted, int rejected, Instant serverTime) {}
```

服务端未返回 `server_time` 时，`serverTime` 为 null。

## 6. 模型

`com.cdeer.shieldx.model` 下的 record：

- `PolicySnapshot`、`Policy`、`Match`、`Condition`、`Action`、`ActionParams`
- `TemplateSnapshot`、`Template`
- `Event`、`EventBatch`、`ClientStats`
- 字符串常量：`Actions`、`ActionResults`、`DecisionSources`

`Condition.value` 与开放的动作参数使用 `JsonNode`，保留未知字段。可选字段省略；
`matched_policy_id` 始终输出，未命中时为 `null`。

### 事件校验

`Event.validate()` 校验事件 ID 长度、时间戳、客户端 IP、host/path/method、动作/结果/决策来源，
以及三态规则：`decision_source=policy` 时必须提供非空策略 ID；其他来源不能带非空策略 ID。

## 7. 异常

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

- `TransportException`：网络、超时、TLS、DNS、响应过大；可重试。
- `ValidationException`：事件/批次校验失败，包含事件下标与字段。
- `BatchTooLargeException`：超过 1000 条或压缩前 1 MiB。

## 8. Poller

```java
try (Poller poller = Poller.builder(client)
        .interval(Duration.ofSeconds(5))
        .initialRevision(savedRevision)
        .onSnapshot(store::replace)
        .onError(log::warn)
        .build()) {
    poller.start();
}
```

- 单 daemon 线程 `shieldx-poller`
- 立即首拉
- 304 保留旧快照；空 `policies` 清空
- 失败保留旧快照，1s → 60s 退避 + jitter
- `refresh()`、`refreshTemplates()`、`snapshot()`、`stats()`
- `close()` 幂等并等待结束

## 9. Reporter

```java
try (Reporter reporter = Reporter.builder(client)
        .batchSize(300)
        .flushInterval(Duration.ofSeconds(2))
        .queueSize(10_000)
        .state(() -> new ReporterState(rev, count))
        .build()) {
    reporter.start();
    reporter.enqueue(event);
}
```

- 单 daemon 线程 `shieldx-reporter`
- 有界队列；`enqueue` 不阻塞；满时丢最旧
- 413 拆半；400 丢弃；429/5xx/网络退避或落 spool
- `close(Duration)` 尽力 flush
- 统计：enqueued/dropped/sent/accepted/rejected/spooled/replayed

## 10. Spool

`FileSpool` 每个批次一个 gzip JSON 文件
（`v1-<epochMillis>-<seq>-<events>.json.gz`）。临时文件 + 原子 rename，目录 0700/文件 0600，
按文件数或字节淘汰，最旧优先补发；损坏文件删除。

## 11. Spring Boot Starter

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

Starter 创建 client/poller/reporter Bean，通过 `SmartLifecycle` 启停，并提供 Actuator Health
与 Micrometer 指标。它不注册快照回调；业务代码读取 `poller.snapshot()`。

## 12. 模板渲染

`TemplateRenderer.render(template, vars)` 实现契约渲染规则：BOM 移除、65,536 字节上限、
7 个白名单占位符、单遍替换、HTML 转义。通过 14 条向量。该模块可选。

## 13. 线程与 HTTP

- `ShieldXClient` 不可变、线程安全。
- Poller/Reporter 各一个 daemon 线程。
- 不要在响应式 event loop 上阻塞；`enqueue` 非阻塞。
- 禁止自动重定向；不允许关闭 TLS 校验。
- 响应上限 32 MiB；批次 1000 条 / 1 MiB。
- Token 不写日志，不进入异常消息。

## 14. 兼容性

| client-java | 契约 | shield-x | Java |
|---|---|---|---|
| `0.1.x` | `agent-v1` | `0.9.x` | 17 / 21 |

版本说明见 [CHANGELOG](../../CHANGELOG.zh-CN.md)。

## 15. 探针

裸 HTTP 探针位于 `core/src/test/java/com/cdeer/shieldx/probe/ContractProbeTest.java`，
不 import SDK。通过 `SHIELDX_BASE_URL`、`SHIELDX_TOKEN`，以及可选的
`SHIELDX_PROBE_EMPTY_TOKEN` / `SHIELDX_PROBE_TOKEN_B` 运行。
