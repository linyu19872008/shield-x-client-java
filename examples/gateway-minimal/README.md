# gateway-minimal

最小接入示例：只看 `Poller` / `Reporter` 如何接线，不包含真实策略求值与渲染。

```bash
export SHIELD_X_TOKEN=sx_xxx
export SHIELD_X_BASE_URL=http://127.0.0.1:8080/shieldx
./mvnw -pl examples/gateway-minimal exec:java \
  -Dexec.mainClass=com.cdeer.shieldx.examples.MinimalGateway
```

真实网关还需要：

- 把 `poller.snapshot()` 交给本地策略引擎；
- 在请求路径上执行动作；
- 构造 `Event` 并 `reporter.enqueue(event)`；
- 按需使用 `shield-x-template` 渲染拦截页。
