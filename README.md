# shield-x-client-java

Java client for the **shield-x** `agent-v1` gateway contract.

> 开发中，`v0.1.0` 尚未发布。SDK 只负责协议与传输；策略求值、模板渲染与拦截执行仍由网关负责。

- 规划：[docs/planning-v0.1.md](docs/planning-v0.1.md)
- 参考实现：[shield-x-client-go](https://github.com/linyu19872008/shield-x-client-go)

## 构建

要求 JDK 17+ 与 Maven 3.9+：

```bash
./mvnw -B verify
```

## 模块

| 模块 | 说明 |
|---|---|
| `core` | `ShieldXClient`、模型、poller、reporter、spool |
| `spring-boot-starter` | 二期 |
| `template` | 可选参考渲染器 |

## License

[MIT](LICENSE)
