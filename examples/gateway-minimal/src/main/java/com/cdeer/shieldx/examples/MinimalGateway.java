package com.cdeer.shieldx.examples;

import com.cdeer.shieldx.client.Capabilities;
import com.cdeer.shieldx.client.ShieldXClient;
import com.cdeer.shieldx.model.PolicySnapshot;
import com.cdeer.shieldx.poller.Poller;
import com.cdeer.shieldx.reporter.Reporter;
import com.cdeer.shieldx.reporter.ReporterState;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal poller + reporter wiring; a real gateway also evaluates policies and renders templates.
 */
public final class MinimalGateway {

  private MinimalGateway() {}

  public static void main(String[] args) throws Exception {
    String baseUrl = env("SHIELD_X_BASE_URL", "http://127.0.0.1:8080/shieldx");
    String token = System.getenv("SHIELD_X_TOKEN");
    if (token == null || token.isBlank()) {
      System.err.println("set SHIELD_X_TOKEN to a tenant token");
      return;
    }
    String gatewayId = env("SHIELD_X_GATEWAY_ID", "gw-example-01");
    String agentVersion = env("SHIELD_X_AGENT_VERSION", "gateway-example/0.1.0");

    ShieldXClient client =
        ShieldXClient.builder()
            .baseUrl(baseUrl)
            .token(token)
            .gatewayId(gatewayId)
            .agentVersion(agentVersion)
            .capabilities(
                Capabilities.builder()
                    .actions("observe", "block")
                    .matchFields("ip", "host", "path")
                    .maxRules(5000)
                    .build())
            .build();

    AtomicReference<PolicySnapshot> snapshot = new AtomicReference<>();
    try (Poller poller =
            Poller.builder(client)
                .interval(Duration.ofSeconds(5))
                .onSnapshot(snapshot::set)
                .onError(error -> System.err.println("poll failed: " + error.getMessage()))
                .build();
        Reporter reporter =
            Reporter.builder(client)
                .flushInterval(Duration.ofSeconds(2))
                .state(
                    () -> {
                      PolicySnapshot current = snapshot.get();
                      return current == null
                          ? new ReporterState(0, 0)
                          : new ReporterState(current.revision(), current.policies().size());
                    })
                .onError(error -> System.err.println("report failed: " + error.getMessage()))
                .build()) {
      poller.start();
      reporter.start();
      System.out.println("started; press Ctrl+C to stop");
      Thread.sleep(Long.MAX_VALUE);
    }
  }

  private static String env(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
