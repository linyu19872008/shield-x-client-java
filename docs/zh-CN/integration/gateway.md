# gateway 接入指南（SDK 侧）

[English](../../integration/gateway.md) | **简体中文**

> 状态：指南草案。**本仓库不修改 gateway 代码**；本文只描述接入方需要做的事。
> SDK：`io.github.linyu19872008:shield-x-client-java:0.1.0`
> 契约：`agent-v1`

---

## 1. 接入前确认

| 项 | 要求 |
|---|---|
| Java | 17+（gateway 是 Java 21，可直接使用） |
| Spring Boot | 3.2.x（starter 的验证基线） |
| shield-x | 已部署，base URL 含 `/shieldx` 前缀 |
| 站点 | 已创建 Token，且网关具备稳定 `gatewayId` |
| 能力 | 明确 actions / match_fields / 模板支持 |

需要先决定：

- gateway 是否使用 `shield-x-spring-boot-starter`，还是只用 core；
- 是否使用 `shield-x-template` 做拦截页渲染；
- 观测口径（304 比例、队列深度、spool、丢弃）。

---

## 2. 引入依赖

```xml
<dependency>
  <groupId>io.github.linyu19872008</groupId>
  <artifactId>shield-x-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

只使用 core 时：

```xml
<dependency>
  <groupId>io.github.linyu19872008</groupId>
  <artifactId>shield-x-client-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

> 当前两个仓库都是 private。接入方需要 GitHub 访问权限，或由发布方把制品推到内部
> Maven 仓库 / Maven Central。

---

## 3. 配置

```yaml
shield-x:
  enabled: true
  base-url: http://shield-x.internal:8080/shieldx
  token: ${SHIELD_X_TOKEN}
  gateway-id: gw-prod-01
  agent-version: gateway/2.1.1
  connect-timeout: 3s
  request-timeout: 10s
  capabilities:
    actions: [observe, block, redirect]
    match-fields: [ip, host, path, method, header]
    block-templates: true
    template-spec-version: 1
    max-rules: 5000
  poll:
    enabled: true
    interval: 5s
    fetch-templates: true
  report:
    enabled: true
    batch-size: 300
    flush-interval: 2s
    queue-size: 10000
    spool-dir: ./data/spool
    format: ndjson
    gzip: true
    shutdown-timeout: 5s
```

`token` 建议从环境变量/密钥管理注入，不要写进配置文件。

---

## 4. 使用方式

Starter 会创建：

- `ShieldXClient`
- `Poller`（`shield-x.poll.enabled=true`）
- `Reporter`（`shield-x.report.enabled=true`）
- `ShieldXLifecycle`（启动 poller → reporter，关闭 reporter → poller）
- `ShieldXHealthIndicator` / `ShieldXMetrics`（依赖 Actuator/Micrometer）

gateway 中注入：

```java
@Service
public class ShieldXState {
  private final Poller poller;

  public ShieldXState(Poller poller) {
    this.poller = poller;
  }

  public PolicySnapshot current() {
    return poller.snapshot();
  }
}
```

> Starter 当前不注册 `onSnapshot` 回调，策略快照通过 `poller.snapshot()` 读取。
> 如果 gateway 需要“快照变化时重建索引”，在自身 Bean 中定时读取或改用 core 手工构造 Poller。

请求路径：

```java
Reporter reporter; // 注入

Event event = Event.builder()
    .eventId(UUID.randomUUID().toString()) // 必须稳定、1..64
    .ts(Instant.now())
    .clientIp(clientIp)
    .host(host)
    .path(path)
    .method(method)
    .status(status)
    .durationMs((int) durationMs)
    .action(action)
    .actionResult(result)
    .decisionSource(source)
    .matchedPolicyId(policyId) // 未命中必须为 null
    .configRevision(rev)
    .build();

reporter.enqueue(event);
```

**不要在 WebFlux event loop 上做阻塞调用。** `enqueue` 非阻塞；poller/reporter 使用自己的
daemon 线程。gateway 的过滤器只做内存判断 + `enqueue`。

---

## 5. 职责划分

| 组件 | 职责 |
|---|---|
| `shield-x-client-java` | HTTP、304、编码、重试、spool、batch 元信息 |
| gateway | 策略求值、first-match、动作执行、模板渲染、事件字段构造 |
| `shield-x-template`（可选） | 参考渲染器，替 gateway 完成 HTML 转义/占位符 |
| shield-x | 策略/模板下发、事件接收、控制台 |

SDK **不**提供策略引擎，也不替 gateway 决定 `fail_open` / `fail_closed`。

---

## 6. 验收清单

在 gateway 真实流量上至少验证：

- [ ] 首拉 200，`revision` 与策略数正确，顺序不被 SDK 重排
- [ ] 第二次拉取带 `If-None-Match`，服务端 304 时本地快照不变
- [ ] 空策略站点清空本地策略
- [ ] Token 无效时 401，不丢事件、不刷屏
- [ ] NDJSON + gzip 事件被接收，`accepted/rejected` 正确
- [ ] 413 自动拆半，单条仍 413 时丢弃并告警
- [ ] 429 按 `Retry-After` 退避
- [ ] 断网/5xx 时事件落 spool，恢复后补发
- [ ] 队列满时丢最旧并暴露 `dropped`
- [ ] 关闭时 reporter 先 flush，poller 后停
- [ ] `matched_policy_id` 三态正确（null / policy / gateway_error）
- [ ] 模板 14 条向量通过（若声明 `block_templates=true`）
- [ ] 指标无 token/高基数标签
- [ ] Spool 目录权限 0700、文件 0600

---

## 7. 灰度与回滚

1. **shadow**：只拉策略、只上报 observe 事件，不执行新动作；
2. **observe**：只记录决策，不改变响应；
3. **小流量 block**：按站点/网关灰度；
4. 异常时把 `shield-x.enabled=false` 或回滚 gateway 版本。

SDK 的网络/策略失败不会阻塞业务请求；但 gateway 的本地策略引擎仍需定义
`fail_open` / `fail_closed`，这是 gateway 的责任。

---

## 8. 观测

- `shieldx.poll.success/not_modified/failures/last_revision`
- `shieldx.report.queue.depth/dropped/accepted/rejected`
- `shieldx.report.spool.files/bytes`
- Health details：`stale`、`lastSuccess`、`lastError`

告警建议：

- 连续 3 个 poll 周期无成功；
- `dropped_total` 增长；
- spool 文件数/字节持续增长；
- 401/403 持续出现（Token 过期/轮换）。

---

## 9. 已知限制

- P8（429 + `Retry-After`）与 P9（跨站点隔离）在 SDK CI 中依赖服务端限流/种子策略，
  本地 probe 会显式 Skip 或按环境执行。
- SDK 不持久化快照；`InitialRevision` 需要 gateway 自己从磁盘恢复。
- SDK 不保证 exactly-once；重试与 spool 补发可能重复，gateway 需要接受 at-least-once。
- 当前制品为 private；公开分发前需要确认仓库可见性/发布渠道。
