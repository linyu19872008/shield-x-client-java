package com.cdeer.shieldx.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class ShieldXConfigTest {

  @Test
  void baseUrlMustContainShieldxPrefix() {
    assertThatThrownBy(
            () ->
                ShieldXConfig.builder()
                    .baseUrl("http://localhost:8080")
                    .token("sx")
                    .gatewayId("gw")
                    .agentVersion("v1")
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/shieldx");
  }

  @Test
  void tokenIsRequired() {
    assertThatThrownBy(
            () ->
                ShieldXConfig.builder()
                    .baseUrl("http://localhost:8080/shieldx")
                    .gatewayId("gw")
                    .agentVersion("v1")
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("token");
  }

  @Test
  void gatewayIdIsValidated() {
    assertThatThrownBy(
            () ->
                ShieldXConfig.builder()
                    .baseUrl("http://localhost:8080/shieldx")
                    .token("sx")
                    .gatewayId("bad id")
                    .agentVersion("v1")
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gatewayId");
  }

  @Test
  void tokenAndSupplierAreMutuallyExclusive() {
    assertThatThrownBy(
            () ->
                ShieldXConfig.builder()
                    .baseUrl("http://localhost:8080/shieldx")
                    .token("sx")
                    .tokenSupplier(() -> "sx2")
                    .gatewayId("gw")
                    .agentVersion("v1")
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void tokenSupplierIsEvaluatedPerRequest() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse().setResponseCode(200).setBody("{\"revision\":1,\"policies\":[]}"));
      server.enqueue(
          new MockResponse().setResponseCode(200).setBody("{\"revision\":2,\"policies\":[]}"));
      AtomicInteger calls = new AtomicInteger();
      ShieldXClient client =
          ShieldXClient.builder()
              .baseUrl(server.url("/shieldx").toString())
              .tokenSupplier(() -> "sx-" + calls.incrementAndGet())
              .gatewayId("gw")
              .agentVersion("v1")
              .build();

      client.fetchPolicies(-1);
      client.fetchPolicies(-1);

      assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer sx-1");
      assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer sx-2");
    }
  }

  @Test
  void defaultsAreApplied() {
    ShieldXConfig config =
        ShieldXConfig.builder()
            .baseUrl("http://localhost:8080/shieldx/")
            .token("sx")
            .gatewayId("gw")
            .agentVersion("v1")
            .build();

    assertThat(config.baseUrl()).isEqualTo("http://localhost:8080/shieldx");
    assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(config.maxResponseBytes()).isEqualTo(32L * 1024 * 1024);
    assertThat(config.userAgent()).startsWith("shield-x-client-java/");
  }
}
