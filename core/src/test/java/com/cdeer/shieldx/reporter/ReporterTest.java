package com.cdeer.shieldx.reporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.cdeer.shieldx.client.ShieldXClient;
import com.cdeer.shieldx.model.Event;
import com.cdeer.shieldx.spool.FileSpool;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReporterTest {

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
  void sendsBatchWithStateAndMetadata() throws Exception {
    server.enqueue(
        new MockResponse().setResponseCode(202).setBody("{\"accepted\":2,\"rejected\":0}"));
    Reporter reporter =
        Reporter.builder(client)
            .flushInterval(Duration.ofMillis(50))
            .state(() -> new ReporterState(39, 2))
            .build();

    reporter.start();
    reporter.enqueue(event("evt-1"));
    reporter.enqueue(event("evt-2"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(reporter.stats().accepted()).isEqualTo(2);
              assertThat(reporter.stats().sent()).isEqualTo(1);
            });

    RecordedRequest request = server.takeRequest();
    assertThat(request.getPath()).isEqualTo("/shieldx/events/v1");
    String body = gunzip(request.getBody().readByteArray());
    assertThat(body).contains("\"config_revision\":39");
    assertThat(body).contains("\"policy_count\":2");
    assertThat(body).contains("\"agent_version\":\"gateway/1.0\"");
    assertThat(body).contains("\"gateway_id\":\"gw-test\"");
    reporter.close();
  }

  @Test
  void queueFullDropsOldest() {
    Reporter reporter = Reporter.builder(client).queueSize(2).build();
    reporter.enqueue(event("evt-1"));
    reporter.enqueue(event("evt-2"));
    reporter.enqueue(event("evt-3"));

    ReporterStats stats = reporter.stats();
    assertThat(stats.queueDepth()).isEqualTo(2);
    assertThat(stats.dropped()).isEqualTo(1);
    reporter.close();
  }

  @Test
  void invalidEventIsRejectedWithoutEnqueue() {
    Reporter reporter = Reporter.builder(client).build();
    Event invalid = Event.builder().eventId("bad").build();

    assertThat(reporter.enqueue(invalid)).isFalse();
    assertThat(reporter.stats().dropped()).isEqualTo(1);
    reporter.close();
  }

  @Test
  void batchTooLargeIsSplitAndRetried() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(413)
            .setBody("{\"error\":{\"code\":\"BATCH_TOO_LARGE\"}}"));
    server.enqueue(
        new MockResponse().setResponseCode(202).setBody("{\"accepted\":1,\"rejected\":0}"));
    server.enqueue(
        new MockResponse().setResponseCode(202).setBody("{\"accepted\":1,\"rejected\":0}"));
    Reporter reporter = Reporter.builder(client).flushInterval(Duration.ofMillis(50)).build();

    reporter.enqueue(event("evt-1"));
    reporter.enqueue(event("evt-2"));
    reporter.start();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(reporter.stats().accepted()).isEqualTo(2);
              assertThat(reporter.stats().sent()).isEqualTo(2);
            });
    assertThat(server.getRequestCount()).isEqualTo(3);
    reporter.close();
  }

  @Test
  void serverRejectedBatchIsDropped() {
    server.enqueue(
        new MockResponse().setResponseCode(400).setBody("{\"error\":{\"code\":\"BAD_REQUEST\"}}"));
    AtomicReference<String> reason = new AtomicReference<>();
    Reporter reporter =
        Reporter.builder(client)
            .flushInterval(Duration.ofMillis(50))
            .onDrop((count, why) -> reason.set(why))
            .build();

    reporter.start();
    reporter.enqueue(event("evt-1"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(reporter.stats().dropped()).isEqualTo(1);
              assertThat(reason.get()).isEqualTo("server_rejected");
            });
    reporter.close();
  }

  @Test
  void failureSpoolsBatch(@TempDir Path dir) {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
    FileSpool spool = new FileSpool(dir);
    Reporter reporter =
        Reporter.builder(client).flushInterval(Duration.ofMillis(50)).spool(spool).build();

    reporter.start();
    reporter.enqueue(event("evt-1"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(reporter.stats().spooled()).isEqualTo(1);
              assertThat(spool.stats().files()).isEqualTo(1);
            });
    reporter.close();
  }

  @Test
  void spoolIsReplayedOnStart(@TempDir Path dir) throws IOException {
    FileSpool spool = new FileSpool(dir);
    spool.save(new com.cdeer.shieldx.model.EventBatch(39, 1, null, List.of(event("evt-old"))));
    server.enqueue(
        new MockResponse().setResponseCode(202).setBody("{\"accepted\":1,\"rejected\":0}"));
    Reporter reporter =
        Reporter.builder(client).flushInterval(Duration.ofMillis(50)).spool(spool).build();

    reporter.start();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(reporter.stats().replayed()).isEqualTo(1);
              assertThat(spool.stats().files()).isZero();
            });
    reporter.close();
  }

  @Test
  void doubleStartAndStartAfterCloseAreRejected() {
    Reporter reporter = Reporter.builder(client).flushInterval(Duration.ofMillis(20)).build();
    reporter.start();
    org.assertj.core.api.Assertions.assertThatThrownBy(reporter::start)
        .isInstanceOf(IllegalStateException.class);
    reporter.close();
    org.assertj.core.api.Assertions.assertThatThrownBy(reporter::start)
        .isInstanceOf(IllegalStateException.class);
  }

  private static Event event(String id) {
    return Event.builder()
        .eventId(id)
        .ts(Instant.parse("2026-11-14T16:00:00Z"))
        .clientIp("203.0.113.10")
        .host("example.com")
        .path("/")
        .method("GET")
        .status(200)
        .durationMs(1)
        .configRevision(39)
        .action("observe")
        .actionResult("passed")
        .decisionSource("none")
        .build();
  }

  private static String gunzip(byte[] body) {
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
