package com.cdeer.shieldx.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Raw HTTP contract probe. Deliberately does not import the SDK, so a bug in the SDK cannot mask a
 * server bug (and vice versa).
 */
@EnabledIfEnvironmentVariable(named = "SHIELDX_BASE_URL", matches = ".+")
class ContractProbeTest {

  private static final String BASE = trimSlash(System.getenv("SHIELDX_BASE_URL"));
  private static final String TOKEN = System.getenv("SHIELDX_TOKEN");
  private static final String EMPTY_TOKEN = System.getenv("SHIELDX_PROBE_EMPTY_TOKEN");
  private static final String TOKEN_B = System.getenv("SHIELDX_PROBE_TOKEN_B");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @BeforeEach
  void requireToken() {
    assumeTrue(TOKEN != null && !TOKEN.isBlank(), "SHIELDX_TOKEN is required");
  }

  @Test
  void p1MissingTokenIsUnauthorized() throws Exception {
    assertThat(get("/policies/v1", null).statusCode()).isEqualTo(401);
  }

  @Test
  void p2PoliciesSnapshotAndEtag() throws Exception {
    HttpResponse<String> response = get("/policies/v1", TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode policies = MAPPER.readTree(response.body()).get("policies");
    assumeTrue(policies != null && policies.size() > 0, "site needs at least one policy");
    assertThat(response.headers().firstValue("ETag")).isPresent();
  }

  @Test
  void p3ConditionalGetReturns304() throws Exception {
    HttpResponse<String> first = get("/policies/v1", TOKEN);
    assumeTrue(first.statusCode() == 200, "policies endpoint not available");
    String etag = first.headers().firstValue("ETag").orElse("");
    assumeTrue(!etag.isBlank(), "server did not return ETag");

    HttpResponse<String> second = get("/policies/v1", TOKEN, "If-None-Match", etag);
    assertThat(second.statusCode()).isEqualTo(304);
    assertThat(second.body()).isEmpty();
  }

  @Test
  void p4EmptySiteReturnsEmptyPolicies() throws Exception {
    assumeTrue(EMPTY_TOKEN != null && !EMPTY_TOKEN.isBlank(), "empty site token not set");
    HttpResponse<String> response = get("/policies/v1", EMPTY_TOKEN);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode policies = MAPPER.readTree(response.body()).get("policies");
    assertThat(policies).isNotNull();
    assertThat(policies.size()).isZero();
  }

  @Test
  void p5JsonBatchAccepted() throws Exception {
    assertThat(postJson("/events/v1", jsonBatch(2), TOKEN).statusCode()).isEqualTo(202);
  }

  @Test
  void p6NdjsonGzipAccepted() throws Exception {
    HttpResponse<String> response = postNdjsonGzip("/events/v1", ndjsonBatch(2), TOKEN);
    assertThat(response.statusCode()).isEqualTo(202);
  }

  @Test
  void p7TooManyEventsReturns413() throws Exception {
    HttpResponse<String> response = postJson("/events/v1", jsonBatch(1001), TOKEN);
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(MAPPER.readTree(response.body()).at("/error/code").asText())
        .isEqualTo("BATCH_TOO_LARGE");
  }

  @Test
  void p8RateLimitIsEnvironmentDependent() {
    assumeTrue(false, "requires server-side rate limiting or queue pressure");
  }

  @Test
  void p9SiteIsolation() throws Exception {
    assumeTrue(TOKEN_B != null && !TOKEN_B.isBlank(), "second site token not set");
    Set<String> idsA = policyIds(TOKEN);
    Set<String> idsB = policyIds(TOKEN_B);
    assumeTrue(!idsA.isEmpty() && !idsB.isEmpty(), "both sites need policies");
    idsA.retainAll(idsB);
    assertThat(idsA).isEmpty();
  }

  @Test
  void p10InvalidBodyReturns400() throws Exception {
    assertThat(postJson("/events/v1", "{\"events\":", TOKEN).statusCode()).isEqualTo(400);
  }

  private Set<String> policyIds(String token) throws Exception {
    HttpResponse<String> response = get("/policies/v1", token);
    assertThat(response.statusCode()).isEqualTo(200);
    Set<String> ids = new HashSet<>();
    for (JsonNode policy : MAPPER.readTree(response.body()).path("policies")) {
      ids.add(policy.path("id").asText());
    }
    return ids;
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    return send(request(path, token).GET().build());
  }

  private HttpResponse<String> get(String path, String token, String header, String value)
      throws Exception {
    return send(request(path, token).header(header, value).GET().build());
  }

  private HttpResponse<String> postJson(String path, String body, String token) throws Exception {
    return send(
        request(path, token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private HttpResponse<String> postNdjsonGzip(String path, String body, String token)
      throws Exception {
    return send(
        request(path, token)
            .header("Content-Type", "application/x-ndjson")
            .header("Content-Encoding", "gzip")
            .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(body)))
            .build());
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path, String token) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-Shieldx-Gateway-Id", "gw-java-probe")
            .header("X-Shieldx-Agent-Version", "probe/0.1.0");
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return builder;
  }

  private static String jsonBatch(int count) throws Exception {
    ObjectNode root = baseBatch();
    ArrayNode events = MAPPER.createArrayNode();
    for (int i = 0; i < count; i++) {
      events.add(event("evt-" + i));
    }
    root.set("events", events);
    return MAPPER.writeValueAsString(root);
  }

  private static String ndjsonBatch(int count) throws Exception {
    ObjectNode meta = MAPPER.createObjectNode();
    meta.put("agent_version", "probe/0.1.0");
    meta.put("gateway_id", "gw-java-probe");
    meta.put("config_revision", 0);
    meta.put("policy_count", 0);
    StringBuilder sb = new StringBuilder(MAPPER.writeValueAsString(meta)).append('\n');
    for (int i = 0; i < count; i++) {
      sb.append(MAPPER.writeValueAsString(event("evt-" + i))).append('\n');
    }
    return sb.toString();
  }

  private static ObjectNode baseBatch() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("config_revision", 0);
    root.put("policy_count", 0);
    return root;
  }

  private static ObjectNode event(String id) {
    ObjectNode event = MAPPER.createObjectNode();
    event.put("event_id", id);
    event.put("ts", Instant.parse("2026-11-14T16:00:00Z").toString());
    event.put("client_ip", "203.0.113.10");
    event.put("host", "example.com");
    event.put("path", "/");
    event.put("method", "GET");
    event.put("status", 200);
    event.put("duration_ms", 1);
    event.putNull("matched_policy_id");
    event.put("config_revision", 0);
    event.put("action", "observe");
    event.put("action_result", "passed");
    event.put("decision_source", "none");
    return event;
  }

  private static byte[] gzip(String value) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(value.getBytes(StandardCharsets.UTF_8));
    }
    return out.toByteArray();
  }

  private static String trimSlash(String value) {
    if (value == null) {
      return null;
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
