# shield-x-client-java

[![CI](https://github.com/linyu19872008/shield-x-client-java/actions/workflows/ci.yml/badge.svg)](https://github.com/linyu19872008/shield-x-client-java/actions/workflows/ci.yml)
[![Contract Probe](https://github.com/linyu19872008/shield-x-client-java/actions/workflows/contract-probe.yml/badge.svg)](https://github.com/linyu19872008/shield-x-client-java/actions/workflows/contract-probe.yml)
[![Release](https://img.shields.io/github/v/release/linyu19872008/shield-x-client-java)](https://github.com/linyu19872008/shield-x-client-java/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net)
[![Contract](https://img.shields.io/badge/contract-agent--v1-blue.svg)](docs/zh-CN/client-java-api-v1.md)

[English](README.md) | **简体中文**

用于对接 **shield-x** `agent-v1` 网关契约的 Java 客户端：拉策略/模板、批量上报事件、可选轮询/重试/spool，以及 Spring Boot Starter。

> **状态：私有预览（`v0.1.0`）。** 制品尚未发布到 Maven Central；请先申请仓库权限或从源码构建，见[安装](#安装)。SDK 只负责协议与传输，策略求值与模板执行仍由网关负责。

- 包根：`com.cdeer.shieldx`
- Maven：`io.github.linyu19872008:shield-x-client-java`
- API 指南：[docs/zh-CN/client-java-api-v1.md](docs/zh-CN/client-java-api-v1.md)
- 许可证：[MIT](LICENSE)

## 特性

- 拉策略/模板：304、ETag、revision 0、空策略清空、失败保留旧快照
- 上报事件：JSON/NDJSON、gzip、1000 条 / 1 MiB 限制、413 拆半、429 退避、400 丢弃
- 可选 `Poller`：定时拉取、指数退避、jitter、模板刷新
- 可选 `Reporter`：有界队列、丢最旧、spool 补发、优雅关闭
- HTTP 安全默认值：禁止重定向、TLS 校验、响应上限、Token 脱敏
- Spring Boot Starter：配置绑定、生命周期、Health、Micrometer
- 可选 `shield-x-template`：参考渲染器，14 条一致性向量全部通过

## 要求

- Java 17+（Java 21 可直接使用）
- Maven 3.9+（仓库自带 `mvnw`）
- 已部署的 shield-x 与租户 Token

## 安装

仓库目前是私有预览：

```xml
<dependency>
  <groupId>io.github.linyu19872008</groupId>
  <artifactId>shield-x-client-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

制品发布前，请 clone 仓库并在根目录执行 `./mvnw install`。Maven Central / GitHub Packages 的发布安排在项目路线图中跟踪。

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

`sinceRevision = -1` 表示没有本地快照；`>= 0` 时发送 `If-None-Match: "rev-N"`，revision 0 合法。
304 不是异常：`PoliciesResult.notModified()` 为 true。

## 上报语义

- at-least-once：重试与 spool 补发可能产生重复事件；`event_id` 由网关生成且必须稳定。
- 队列满丢最旧并计入 `dropped`；400 丢弃该批；413 自动拆半；429/5xx/网络按策略退避或落 spool。
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

## 模块

| 模块 | 说明 |
|---|---|
| `core` | client、模型、poller、reporter、spool |
| `spring-boot-starter` | Spring Boot 集成 |
| `template` | 可选参考渲染器 |
| `examples/gateway-minimal` | 最小接入示例 |

## 构建与测试

```bash
./mvnw -B verify
```

## 契约探针

需要真实 shield-x：

```bash
SHIELDX_BASE_URL=http://127.0.0.1:8080/shieldx \
SHIELDX_TOKEN=sx_xxx \
SHIELDX_PROBE_EMPTY_TOKEN=sx_empty \
SHIELDX_PROBE_TOKEN_B=sx_other \
./mvnw -pl core -Dtest=ContractProbeTest test
```

CI 通过 `scripts/probe-ci.sh` 启动 MySQL 与固定提交的 shield-x，再执行同一组探针。

## 兼容性

| client-java | 契约 | shield-x | Java |
|---|---|---|---|
| `0.1.x` | `agent-v1` | `0.9.x` | 17 / 21 |

`0.1.x` 是早期版本线，minor 升级可能包含破坏性调整；升级前请查看 [CHANGELOG](CHANGELOG.zh-CN.md)。

## 文档

- [API 指南](docs/zh-CN/client-java-api-v1.md)
- [文档索引](docs/zh-CN/README.md)
- [接入指南](docs/zh-CN/integration/gateway.md)
- [变更记录](CHANGELOG.zh-CN.md)

## 贡献与安全

- [贡献指南](CONTRIBUTING.zh-CN.md)
- [安全策略](SECURITY.zh-CN.md)
- [行为准则](CODE_OF_CONDUCT.zh-CN.md)

## 许可证

[MIT](LICENSE) © 2026 linyu19872008
