package com.cdeer.shieldx.poller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cdeer.shieldx.client.ShieldXClient;
import com.cdeer.shieldx.model.PolicySnapshot;
import com.cdeer.shieldx.model.TemplateSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PollerTest {

  private MockWebServer server;
  private ShieldXClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client =
        ShieldXClient.builder()
            .baseUrl(server.url("/shieldx").toString())
            .token("sx_test")
            .gatewayId("gw-test")
            .agentVersion("gateway/1.0")
            .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void refreshAppliesSnapshotAndKeepsServerOrder() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("policies_200.json")));
    Poller poller = Poller.builder(client).build();

    poller.refresh();

    assertThat(poller.snapshot().policies()).hasSize(2);
    assertThat(poller.snapshot().policies().get(0).id()).isEqualTo("pol_01");
    assertThat(poller.revision()).isEqualTo(39);
    assertThat(poller.stats().success()).isEqualTo(1);
    poller.close();
  }

  @Test
  void initialRevisionIsSentAsConditionalHeader() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(304).addHeader("ETag", "\"rev-7\""));
    Poller poller = Poller.builder(client).initialRevision(7).build();

    poller.refresh();

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("If-None-Match")).isEqualTo("\"rev-7\"");
    assertThat(poller.stats().notModified()).isEqualTo(1);
    assertThat(poller.revision()).isEqualTo(7);
    poller.close();
  }

  @Test
  void notModifiedKeepsExistingSnapshot() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("policies_200.json")));
    Poller poller = Poller.builder(client).build();
    poller.refresh();
    PolicySnapshot before = poller.snapshot();

    server.enqueue(new MockResponse().setResponseCode(304).addHeader("ETag", "\"rev-39\""));
    poller.refresh();

    assertThat(poller.snapshot()).isSameAs(before);
    assertThat(poller.stats().notModified()).isEqualTo(1);
    poller.close();
  }

  @Test
  void failureKeepsOldSnapshotAndSetsBackoff() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("policies_200.json")));
    Poller poller = Poller.builder(client).build();
    poller.refresh();
    PolicySnapshot before = poller.snapshot();

    server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
    poller.refresh();

    assertThat(poller.snapshot()).isSameAs(before);
    assertThat(poller.stats().failures()).isEqualTo(1);
    assertThat(poller.stats().currentBackoff()).isPositive();
    poller.close();
  }

  @Test
  void callbackExceptionDoesNotKillRefresh() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("policies_200.json")));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("policies_empty.json")));
    AtomicInteger errors = new AtomicInteger();
    Poller poller =
        Poller.builder(client)
            .onSnapshot(
                snapshot -> {
                  throw new IllegalStateException("callback boom");
                })
            .onError(error -> errors.incrementAndGet())
            .build();

    poller.refresh();
    poller.refresh();

    assertThat(poller.stats().success()).isEqualTo(2);
    assertThat(errors.get()).isEqualTo(2);
    poller.close();
  }

  @Test
  void startPollsImmediatelyAndFetchesTemplates() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("policies_200.json")));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("templates_200.json")));
    AtomicReference<TemplateSnapshot> templates = new AtomicReference<>();
    Poller poller =
        Poller.builder(client)
            .interval(Duration.ofSeconds(5))
            .fetchTemplates(true)
            .onTemplates(templates::set)
            .build();

    poller.start();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(templates.get()).isNotNull();
              assertThat(templates.get().templates()).hasSize(1);
            });
    poller.close();
  }

  @Test
  void startTwiceAndStartAfterCloseAreRejected() {
    Poller poller = Poller.builder(client).interval(Duration.ofSeconds(1)).build();
    poller.start();
    assertThatThrownBy(poller::start).isInstanceOf(IllegalStateException.class);
    poller.close();
    assertThatThrownBy(poller::start).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void invalidOptionsAreRejected() {
    assertThatThrownBy(() -> Poller.builder(client).interval(Duration.ofMillis(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Poller.builder(client).initialRevision(-2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private String fixture(String name) {
    try (InputStream input = getClass().getResourceAsStream("/contract/" + name)) {
      if (input == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
