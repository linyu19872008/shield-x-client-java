package com.cdeer.shieldx.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cdeer.shieldx.error.ShieldXException;
import com.cdeer.shieldx.error.TransportException;
import com.cdeer.shieldx.model.Event;
import com.cdeer.shieldx.model.EventBatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPInputStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShieldXClientTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void fetchPoliciesSendsCapabilitiesAndNoConditionalHeader() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .addHeader("ETag", "\"rev-39\"")
            .setBody(fixture("policies_200.json")));

    ShieldXClient client = clientBuilder().build();
    PoliciesResult result = client.fetchPolicies(-1);

    assertThat(result.notModified()).isFalse();
    assertThat(result.revision()).isEqualTo(39);
    assertThat(result.snapshot().policies()).hasSize(2);
    assertThat(result.snapshot().policies().get(0).id()).isEqualTo("pol_01");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getPath()).isEqualTo("/shieldx/policies/v1");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sx_test");
    assertThat(request.getHeader("X-Shieldx-Gateway-Id")).isEqualTo("gw-test");
    assertThat(request.getHeader("X-Shieldx-Agent-Version")).isEqualTo("gateway/1.0");
    assertThat(request.getHeader("X-Shieldx-Capabilities")).contains("\"max_rules\":100");
    assertThat(request.getHeader("If-None-Match")).isNull();
  }

  @Test
  void fetchPoliciesSendsRevisionZeroAndHandles304() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(304).addHeader("ETag", "\"rev-0\""));

    ShieldXClient client = clientBuilder().build();
    PoliciesResult result = client.fetchPolicies(0);

    assertThat(result.notModified()).isTrue();
    assertThat(result.revision()).isZero();
    assertThat(result.snapshot()).isNull();

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("If-None-Match")).isEqualTo("\"rev-0\"");
  }

  @Test
  void fetchPoliciesFallsBackToSinceRevisionWhen304HasNoEtag() {
    server.enqueue(new MockResponse().setResponseCode(304));

    ShieldXClient client = clientBuilder().build();
    PoliciesResult result = client.fetchPolicies(41);

    assertThat(result.notModified()).isTrue();
    assertThat(result.revision()).isEqualTo(41);
  }

  @Test
  void fetchPoliciesDecodesGzipResponse() {
    byte[] gzipped = gzip(fixture("policies_200.json").getBytes(StandardCharsets.UTF_8));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Encoding", "gzip")
            .setBody(new okio.Buffer().write(gzipped)));

    ShieldXClient client = clientBuilder().build();
    PoliciesResult result = client.fetchPolicies(-1);

    assertThat(result.snapshot().policies()).hasSize(2);
  }

  @Test
  void emptyPolicySnapshotIsLegal() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("policies_empty.json")));

    ShieldXClient client = clientBuilder().build();
    PoliciesResult result = client.fetchPolicies(-1);

    assertThat(result.snapshot().policies()).isEmpty();
    assertThat(result.snapshot().revision()).isEqualTo(40);
  }

  @Test
  void fetchTemplatesDecodesFixture() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("templates_200.json")));

    ShieldXClient client = clientBuilder().build();
    TemplatesResult result = client.fetchTemplates(-1);

    assertThat(result.revision()).isEqualTo(39);
    assertThat(result.snapshot().templates()).hasSize(1);
    assertThat(result.snapshot().templates().get(0).id()).isEqualTo("tpl_01");
  }

  @Test
  void apiErrorCarriesCodeRequestIdAndAuthFlag() {
    server.enqueue(new MockResponse().setResponseCode(401).setBody(fixture("error_401.json")));

    ShieldXClient client = clientBuilder().build();

    assertThatThrownBy(() -> client.fetchPolicies(-1))
        .isInstanceOf(ShieldXException.class)
        .satisfies(
            error -> {
              ShieldXException e = (ShieldXException) error;
              assertThat(e.statusCode()).isEqualTo(401);
              assertThat(e.code()).isEqualTo("UNAUTHORIZED");
              assertThat(e.requestId()).isEqualTo("req-401");
              assertThat(e.authentication()).isTrue();
              assertThat(e.retryable()).isFalse();
            });
  }

  @Test
  void retryAfterSecondsIsParsed() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(429)
            .addHeader("Retry-After", "3")
            .setBody("{\"error\":{\"code\":\"QUEUE_FULL\",\"message\":\"busy\"}}"));

    ShieldXClient client = clientBuilder().build();

    assertThatThrownBy(() -> client.fetchPolicies(-1))
        .isInstanceOf(ShieldXException.class)
        .satisfies(
            error -> {
              ShieldXException e = (ShieldXException) error;
              assertThat(e.retryable()).isTrue();
              assertThat(e.retryAfter()).hasSeconds(3);
            });
  }

  @Test
  void redirectIsNotFollowed() {
    server.enqueue(
        new MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")));
    server.enqueue(new MockResponse().setResponseCode(200));

    ShieldXClient client = clientBuilder().build();

    assertThatThrownBy(() -> client.fetchPolicies(-1))
        .isInstanceOf(ShieldXException.class)
        .satisfies(error -> assertThat(((ShieldXException) error).statusCode()).isEqualTo(302));
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void oversizedResponseFails() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("x".repeat(128)));

    ShieldXClient client = clientBuilder().maxResponseBytes(64).build();

    assertThatThrownBy(() -> client.fetchPolicies(-1))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("exceeds");
  }

  @Test
  void reportEventsUsesNdjsonGzipAndBatchMeta() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(202)
            .setBody("{\"accepted\":1,\"rejected\":0,\"server_time\":\"2026-11-14T16:00:00Z\"}"));

    ShieldXClient client = clientBuilder().build();
    ReportResult result = client.reportEvents(validBatch());

    assertThat(result.accepted()).isEqualTo(1);
    assertThat(result.rejected()).isZero();
    assertThat(result.serverTime()).isEqualTo(Instant.parse("2026-11-14T16:00:00Z"));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/shieldx/events/v1");
    assertThat(request.getHeader("Content-Type")).isEqualTo("application/x-ndjson");
    assertThat(request.getHeader("Content-Encoding")).isEqualTo("gzip");

    String body = gunzip(request.getBody().readByteArray());
    String[] lines = body.split("\n");
    assertThat(lines).hasSize(2);
    ObjectMapper mapper = ShieldXConfig.defaultObjectMapper();
    JsonNode meta = mapper.readTree(lines[0]);
    assertThat(meta.get("agent_version").asText()).isEqualTo("gateway/1.0");
    assertThat(meta.get("gateway_id").asText()).isEqualTo("gw-test");
    assertThat(meta.get("config_revision").asLong()).isEqualTo(39);
    JsonNode event = mapper.readTree(lines[1]);
    assertThat(event.get("event_id").asText()).isEqualTo("evt-1");
    assertThat(event.get("matched_policy_id").asText()).isEqualTo("pol_01");
  }

  @Test
  void reportEventsJsonFormatKeepsNullMatchedPolicyId() throws Exception {
    server.enqueue(
        new MockResponse().setResponseCode(202).setBody("{\"accepted\":1,\"rejected\":0}"));

    Event event =
        Event.builder()
            .eventId("evt-2")
            .ts(Instant.parse("2026-11-14T16:00:00Z"))
            .clientIp("203.0.113.11")
            .host("example.com")
            .path("/")
            .method("GET")
            .status(200)
            .durationMs(5)
            .configRevision(39)
            .action("observe")
            .actionResult("passed")
            .decisionSource("none")
            .build();

    ShieldXClient client = clientBuilder().build();
    client.reportEvents(EventBatch.of(List.of(event)), ReportOptions.json());

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
    String body = gunzip(request.getBody().readByteArray());
    JsonNode root = ShieldXConfig.defaultObjectMapper().readTree(body);
    JsonNode eventNode = root.get("events").get(0);
    assertThat(eventNode.has("matched_policy_id")).isTrue();
    assertThat(eventNode.get("matched_policy_id").isNull()).isTrue();
  }

  @Test
  void reportEvents413RaisesApiError() {
    server.enqueue(new MockResponse().setResponseCode(413).setBody(fixture("error_413.json")));

    ShieldXClient client = clientBuilder().build();

    assertThatThrownBy(() -> client.reportEvents(validBatch()))
        .isInstanceOf(ShieldXException.class)
        .satisfies(
            error -> {
              ShieldXException e = (ShieldXException) error;
              assertThat(e.statusCode()).isEqualTo(413);
              assertThat(e.code()).isEqualTo("BATCH_TOO_LARGE");
            });
  }

  @Test
  void customHttpClientIsUsed() {
    HttpClient httpClient = HttpClient.newHttpClient();
    server.enqueue(new MockResponse().setResponseCode(200).setBody(fixture("policies_empty.json")));

    ShieldXClient client = clientBuilder().httpClient(httpClient).build();
    assertThat(client.fetchPolicies(-1).snapshot().policies()).isEmpty();
  }

  private ShieldXClient.Builder clientBuilder() {
    return ShieldXClient.builder()
        .baseUrl(server.url("/shieldx").toString())
        .token("sx_test")
        .gatewayId("gw-test")
        .agentVersion("gateway/1.0")
        .capabilities(
            Capabilities.builder()
                .actions("observe", "block")
                .matchFields("ip", "path")
                .maxRules(100)
                .build());
  }

  private static EventBatch validBatch() {
    return new EventBatch(39, 1, null, List.of(validEvent()));
  }

  private static Event validEvent() {
    return Event.builder()
        .eventId("evt-1")
        .ts(Instant.parse("2026-11-14T16:00:00Z"))
        .clientIp("203.0.113.10")
        .host("example.com")
        .path("/admin")
        .method("GET")
        .status(403)
        .durationMs(12)
        .matchedPolicyId("pol_01")
        .configRevision(39)
        .action("block")
        .actionResult("executed")
        .decisionSource("policy")
        .build();
  }

  private String fixture(String name) {
    try (InputStream input = getClass().getResourceAsStream("/contract/" + name)) {
      if (input == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read fixture " + name, e);
    }
  }

  private static byte[] gzip(byte[] raw) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
        gzip.write(raw);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String gunzip(byte[] raw) {
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(raw))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
