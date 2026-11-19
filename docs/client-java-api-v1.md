# shield-x-client-java Public API

> Contract: `agent-v1` · Maven: `io.github.linyu19872008:shield-x-client-java` · Status: `0.1.x` preview
>
> English is the default entry point. Chinese translation:
> [zh-CN/client-java-api-v1.md](zh-CN/client-java-api-v1.md).

## 1. Scope

The SDK handles the gateway-side protocol only: policy/template pulls, event reporting,
HTTP encoding, retries, batching and spool. It does **not** evaluate policies, render templates,
or enforce actions.

Package root: `com.cdeer.shieldx`.

## 2. Modules

| Module | Purpose |
|---|---|
| `core` | client, models, poller, reporter, spool |
| `spring-boot-starter` | Spring Boot integration |
| `template` | optional reference renderer |
| `examples/gateway-minimal` | minimal wiring example |

## 3. Client configuration

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

| Setting | Rule |
|---|---|
| `baseUrl` | Absolute `http(s)` URL including `/shieldx`; no userinfo/query/fragment |
| `token` | Static token or `Supplier<String>` for rotation; never logged |
| `gatewayId` | 1–64 chars of `[A-Za-z0-9._-]` |
| `gatewayGroup` | Optional site-local group label (≤64 chars), sent as `X-Shieldx-Gateway-Group` |
| `agentVersion` | Non-blank gateway version |
| `capabilities` | Optional; validated before use |
| `connectTimeout` | Default 3s |
| `requestTimeout` | Default 10s |
| `maxResponseBytes` | Default 32 MiB (after decompression) |
| `httpClient` | Optional injected `HttpClient`; SDK does not close it |

Configuration is immutable after `build()`. Invalid settings fail fast with
`IllegalArgumentException`.

## 4. Client methods

```java
PoliciesResult  fetchPolicies(long sinceRevision)
TemplatesResult fetchTemplates(long sinceRevision)
ReportResult    reportEvents(EventBatch batch)
ReportResult    reportEvents(EventBatch batch, ReportOptions options)
```

- `sinceRevision = -1`: no `If-None-Match`.
- `sinceRevision >= 0`: `If-None-Match: "rev-N"`; revision 0 is legal.
- 304: `notModified=true`, `snapshot=null`, no exception.
- 200: decode the snapshot; body `revision` is authoritative.
- 304 without a valid ETag: fall back to `sinceRevision`.
- Errors: `ShieldXException` with status/code/requestId/retryAfter.

`ReportOptions` defaults to NDJSON + gzip. Use `ReportOptions.json()` or the builder to change it.

## 5. Results

```java
record PoliciesResult(long revision, boolean notModified, PolicySnapshot snapshot) {}
record TemplatesResult(long revision, boolean notModified, TemplateSnapshot snapshot) {}
record ReportResult(int accepted, int rejected, Instant serverTime) {}
```

`serverTime` may be null if the server omits it.

## 6. Models

Records under `com.cdeer.shieldx.model`:

- `PolicySnapshot`, `Policy`, `Match`, `Condition`, `Action`, `ActionParams`
- `TemplateSnapshot`, `Template`
- `Event`, `EventBatch`, `ClientStats`
- `Actions`, `ActionResults`, `DecisionSources` string constants

`Condition.value` and action parameters that are open-ended use `JsonNode` so unknown fields
survive a round trip. Optional fields are omitted; `matched_policy_id` is always emitted and is
`null` when no policy matched.

### Event validation

`Event.validate()` checks event ID length, timestamp, client IP, host/path/method, action/result/
decision values, and the tri-state rule: `decision_source=policy` requires a non-empty policy ID;
other sources must not carry a non-empty policy ID.

## 7. Errors

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

- `TransportException`: network, timeout, TLS, DNS or response-size failure; retryable.
- `ValidationException`: event/batch validation; carries event index and field.
- `BatchTooLargeException`: more than 1000 events or 1 MiB before compression.

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

- single daemon thread `shieldx-poller`
- immediate first poll
- 304 keeps the old snapshot; empty `policies` clears it
- failure keeps the old snapshot and backs off 1s → 60s with jitter
- `refresh()`, `refreshTemplates()`, `snapshot()`, `stats()`
- `close()` is idempotent and waits for termination

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

- single daemon thread `shieldx-reporter`
- bounded queue; enqueue never blocks; full queue drops the oldest event
- 413 splits; 400 drops; 429/5xx/network back off or spool
- `close(Duration)` flushes the queue best-effort
- stats include enqueued/dropped/sent/accepted/rejected/spooled/replayed

## 10. Spool

`FileSpool` stores one gzip JSON batch per file (`v1-<epochMillis>-<seq>-<events>.json.gz`).
It uses temp file + atomic move, directory `0700`/file `0600`, trims by file count or bytes,
and replays oldest-first. Corrupt files are removed.

## 11. Spring Boot starter

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

The starter creates client/poller/reporter beans, starts them via `SmartLifecycle`, and exposes
an Actuator Health indicator and Micrometer gauges. It does not register a snapshot callback;
read `poller.snapshot()` from application code.

## 12. Template renderer

`TemplateRenderer.render(template, vars)` implements the contract rendering rules:
BOM removal, 65,536-byte limit, seven whitelisted placeholders, single-pass replacement and
HTML escaping. It passes the 14 conformance vectors. The module is optional.

## 13. Threading and HTTP

- `ShieldXClient` is immutable and thread-safe.
- Poller and reporter each own one daemon thread.
- Do not block a reactive event loop; enqueue is non-blocking.
- Redirects are never followed; TLS verification cannot be disabled.
- Responses are capped at 32 MiB; event batches are capped at 1000 events / 1 MiB.
- Token is never logged or included in exception messages.

## 14. Compatibility

| client-java | Contract | shield-x | Java |
|---|---|---|---|
| `0.1.x` | `agent-v1` | `0.9.x` | 17 / 21 |

See [CHANGELOG](../CHANGELOG.md) for release notes.

## 15. Probe

The raw HTTP probe is in `core/src/test/java/com/cdeer/shieldx/probe/ContractProbeTest.java`;
it does not import the SDK. Run it with `SHIELDX_BASE_URL`, `SHIELDX_TOKEN`, and optionally
`SHIELDX_PROBE_EMPTY_TOKEN` / `SHIELDX_PROBE_TOKEN_B`.
