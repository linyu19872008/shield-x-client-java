# Gateway Integration Guide

> Status: draft. This guide describes what an integrator does; it does not modify any gateway code.
> SDK: `io.github.linyu19872008:shield-x-client-java:0.1.0` · Contract: `agent-v1`

## 1. Before you start

| Item | Requirement |
|---|---|
| Java | 17+ (Java 21 works) |
| Spring Boot | 3.2.x for the starter |
| shield-x | Deployed; base URL includes `/shieldx` |
| Tenant | Token created; gateway ID is stable |
| Capabilities | Actions, match fields and template support are known |

Decide:

- use `shield-x-spring-boot-starter` or core only;
- use `shield-x-template` or implement rendering in the gateway;
- how to observe 304 ratio, queue depth, spool and drops.

## 2. Dependency

```xml
<dependency>
  <groupId>io.github.linyu19872008</groupId>
  <artifactId>shield-x-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Core only:

```xml
<dependency>
  <groupId>io.github.linyu19872008</groupId>
  <artifactId>shield-x-client-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

The repositories are private during preview; request access or build from source.

## 3. Configuration

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

Inject the token from a secret manager or environment variable; never commit it.

## 4. Wiring

The starter creates:

- `ShieldXClient`
- `Poller` (`shield-x.poll.enabled=true`)
- `Reporter` (`shield-x.report.enabled=true`)
- `ShieldXLifecycle` (start poller → reporter; stop reporter → poller)
- `ShieldXHealthIndicator` / `ShieldXMetrics` when Actuator/Micrometer are present

Read the latest snapshot from application code:

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

The starter does not register an `onSnapshot` callback. If you need to rebuild indexes when the
snapshot changes, read `poller.snapshot()` periodically or build `Poller` from core yourself.

Request path:

```java
Event event = Event.builder()
    .eventId(UUID.randomUUID().toString())
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
    .matchedPolicyId(policyId) // null when no policy matched
    .configRevision(rev)
    .build();

reporter.enqueue(event);
```

`enqueue` is non-blocking. Never block a reactive event loop; the SDK uses its own daemon threads.

## 5. Responsibility split

| Component | Responsibility |
|---|---|
| `shield-x-client-java` | HTTP, 304, encoding, retries, spool, batch metadata |
| gateway | policy evaluation, first-match, action execution, event fields |
| `shield-x-template` (optional) | reference HTML escaping/placeholder rendering |
| shield-x | policy/template distribution, event intake, console |

The SDK does not provide a policy engine or choose `fail_open` / `fail_closed`.

## 6. Acceptance checklist

- [ ] First pull returns 200 with the expected revision and policy count
- [ ] Second pull sends `If-None-Match`; 304 keeps the local snapshot
- [ ] Empty policy list clears local policies
- [ ] Invalid token returns 401 without dropping events
- [ ] NDJSON + gzip events are accepted; accepted/rejected are correct
- [ ] 413 splits; a single event that still gets 413 is dropped with an alert
- [ ] 429 honors `Retry-After`
- [ ] Network/5xx events spool and replay after recovery
- [ ] Queue-full drops the oldest and exposes `dropped`
- [ ] Shutdown flushes the reporter before stopping the poller
- [ ] `matched_policy_id` tri-state is correct
- [ ] The 14 template vectors pass when `block_templates=true`
- [ ] Metrics contain no token or high-cardinality labels
- [ ] Spool directory is 0700 and files are 0600

## 7. Rollout and rollback

1. **Shadow**: pull policies and report observe-only events.
2. **Observe**: record decisions without changing responses.
3. **Small block rollout**: by tenant/gateway.
4. On failure set `shield-x.enabled=false` or roll back the gateway.

SDK network failures must not block business requests. The gateway still defines
`fail_open` / `fail_closed` for its local policy engine.

## 8. Observability

- `shieldx.poll.success` / `not_modified` / `failures` / `last_revision`
- `shieldx.report.queue.depth` / `dropped` / `accepted` / `rejected`
- `shieldx.report.spool.files` / `bytes`
- Health details: `stale`, `lastSuccess`, `lastError`

Alert on:

- no successful poll for 3 intervals;
- growing `dropped_total`;
- growing spool files/bytes;
- persistent 401/403 (token expiry/rotation).

## 9. Known limitations

- P8 (429 + `Retry-After`) and P9 (tenant isolation) depend on server rate limiting/seeded policies.
- The SDK does not persist snapshots; restore `InitialRevision` from gateway storage.
- Delivery is at-least-once; retries/spool may duplicate events.
- The artifact is private during preview; publishing is not done yet.
