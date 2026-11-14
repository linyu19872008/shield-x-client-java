# shield-x-client-java

[![CI](https://github.com/linyu19872008/shield-x-client-java/actions/workflows/ci.yml/badge.svg)](https://github.com/linyu19872008/shield-x-client-java/actions/workflows/ci.yml)
[![Contract Probe](https://github.com/linyu19872008/shield-x-client-java/actions/workflows/contract-probe.yml/badge.svg)](https://github.com/linyu19872008/shield-x-client-java/actions/workflows/contract-probe.yml)

Java client for the **shield-x** `agent-v1` gateway contract.

> 状态：`v0.1.0` 已发布（SDK 侧）；gateway-v2 尚未接入。SDK 只负责协议与传输；策略求值、模板渲染与拦截执行仍由网关负责。

- 包名：`com.cdeer.shieldx`（长期冻结）
- Maven：`io.github.linyu19872008:shield-x-client-java`
- 参考实现：[shield-x-client-go](https://github.com/linyu19872008/shield-x-client-go)
- 规划：[docs/planning-v0.1.md](docs/planning-v0.1.md)
- gateway-v2 接入指南：[docs/gateway-v2-integration.md](docs/gateway-v2-integration.md)

## 特性

- 拉策略 / 拉模板：304、ETag、revision 0、空策略清空、失败保留旧快照
- 上报事件：JSON / NDJSON、gzip、1000 条 / 1 MiB 限制、413 拆半、429 退避、400 丢弃
- 可选 `Poller`：定时拉取、指数退避、jitter、模板刷新
- 可选 `Reporter`：有界队列、丢最旧、spool 补发、优雅关闭
- HTTP 安全默认值：禁止重定向、TLS 校验、响应上限、Token 脱敏
- Spring Boot Starter：配置绑定、生命周期、Health、Micrometer
- 可选 `shield-x-template`：参考渲染器，14 条一致性向量

## 要求

- Java 17+（Java 21 可直接使用）
- Maven 3.9+（仓库自带 `mvnw`）

## 快速开始

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

try (Poller poller = Poller.builder(client)
        .interval(Duration.ofSeconds(5))
        .onSnapshot(store::replace)
        .build();
     Reporter reporter = Reporter.builder(client)
        .state(() -> new ReporterState(store.revision(), store.policyCount()))
        .build()) {

    poller.start();
    reporter.start();
    reporter.enqueue(event);
}
```

`sinceRevision = -1` 表示无本地快照；`>= 0` 时发送 `If-None-Match: "rev-N"`，revision 0 合法。
304 不是异常：`PoliciesResult.notModified()` 为 true。

## 上报语义

- at-least-once：重试与 spool 补发可能产生重复事件；`event_id` 由网关生成且保持稳定。
- 队列满丢最旧，计入 `dropped`；400 丢弃该批；413 自动拆半；429/5xx/网络按策略退避或落 spool。
- 采样租户的 `accepted + rejected` 可能小于发送数，不能当成 SDK 丢数据。

## Spring Boot Starter

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

Starter 会创建 `ShieldXClient` / `Poller` / `Reporter`，通过 `SmartLifecycle` 启动，
并提供 Actuator Health 与 Micrometer 指标。

## 模板渲染

`shield-x-template` 是可选参考实现：

```java
String html = TemplateRenderer.render(template.content(), vars);
```

它通过 `template-conformance/cases.json` 的 14 条向量；gateway 也可以自行实现。

## 构建与测试

```bash
./mvnw -B verify
```

模块：

| 模块 | 说明 |
|---|---|
| `core` | client、模型、poller、reporter、spool |
| `spring-boot-starter` | Spring Boot 集成 |
| `template` | 可选参考渲染器 |
| `examples/gateway-minimal` | 最小接入示例 |

## 契约探针

需要真实 shield-x：

```bash
SHIELDX_BASE_URL=http://127.0.0.1:8080/shieldx \
SHIELDX_TOKEN=sx_xxx \
SHIELDX_PROBE_EMPTY_TOKEN=sx_empty \
SHIELDX_PROBE_TOKEN_B=sx_other \
./mvnw -pl core -Dtest=ContractProbeTest test
```

CI 中由 `scripts/probe-ci.sh` 自动 checkout shield-x + client-go、启动 MySQL 与服务后执行。

## License

[MIT](LICENSE)
