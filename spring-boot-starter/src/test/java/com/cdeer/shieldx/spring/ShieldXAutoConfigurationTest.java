package com.cdeer.shieldx.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.cdeer.shieldx.client.ShieldXClient;
import com.cdeer.shieldx.poller.Poller;
import com.cdeer.shieldx.reporter.Reporter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ShieldXAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ShieldXAutoConfiguration.class));

  @Test
  void createsClientWhenPollerAndReporterAreDisabled() {
    runner
        .withPropertyValues(
            "shield-x.base-url=http://127.0.0.1:18080/shieldx",
            "shield-x.token=sx_test",
            "shield-x.gateway-id=gw-test",
            "shield-x.agent-version=gateway/1.0",
            "shield-x.poll.enabled=false",
            "shield-x.report.enabled=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ShieldXClient.class);
              assertThat(context).doesNotHaveBean(Poller.class);
              assertThat(context).doesNotHaveBean(Reporter.class);
            });
  }

  @Test
  void disabledStarterCreatesNoBeans() {
    runner
        .withPropertyValues("shield-x.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(ShieldXClient.class);
              assertThat(context).doesNotHaveBean(ShieldXLifecycle.class);
            });
  }

  @Test
  void bindsPropertiesAndStartsPoller() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setBody(
                  "{\"revision\":7,\"generated_at\":\"2026-11-14T00:00:00Z\","
                      + "\"policy_count\":0,\"filtered_out\":0,\"policies\":[]}"));
      runner
          .withPropertyValues(
              "shield-x.base-url=" + server.url("/shieldx"),
              "shield-x.token=sx_test",
              "shield-x.gateway-id=gw-test",
              "shield-x.agent-version=gateway/1.0",
              "shield-x.poll.interval=1s",
              "shield-x.report.enabled=false")
          .run(
              context -> {
                assertThat(context).hasSingleBean(Poller.class);
                assertThat(context).hasSingleBean(ShieldXLifecycle.class);
                Poller poller = context.getBean(Poller.class);
                Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(poller.revision()).isEqualTo(7));
                assertThat(poller.snapshot()).isNotNull();
              });
    }
  }

  @Test
  void healthIndicatorAndMetricsAreAvailable() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setBody(
                  "{\"revision\":3,\"generated_at\":\"2026-11-14T00:00:00Z\","
                      + "\"policy_count\":0,\"filtered_out\":0,\"policies\":[]}"));
      runner
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
          .withPropertyValues(
              "shield-x.base-url=" + server.url("/shieldx"),
              "shield-x.token=sx_test",
              "shield-x.gateway-id=gw-test",
              "shield-x.agent-version=gateway/1.0",
              "shield-x.poll.interval=5s",
              "shield-x.report.enabled=false")
          .run(
              context -> {
                assertThat(context).hasSingleBean(ShieldXHealthIndicator.class);
                assertThat(context).hasSingleBean(ShieldXMetrics.class);
                assertThat(
                        context
                            .getBean(ShieldXHealthIndicator.class)
                            .health()
                            .getStatus()
                            .getCode())
                    .isEqualTo("UP");
                SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
                context.getBean(ShieldXMetrics.class).bindTo(registry);
                assertThat(registry.getMeters()).isNotEmpty();
              });
    }
  }
}
