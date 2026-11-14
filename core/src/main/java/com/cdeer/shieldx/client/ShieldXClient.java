package com.cdeer.shieldx.client;

import com.cdeer.shieldx.error.BatchTooLargeException;
import com.cdeer.shieldx.error.ShieldXException;
import com.cdeer.shieldx.error.TransportException;
import com.cdeer.shieldx.internal.BatchEncoder;
import com.cdeer.shieldx.model.EventBatch;
import com.cdeer.shieldx.model.PolicySnapshot;
import com.cdeer.shieldx.model.TemplateSnapshot;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/** Thread-safe client for the shield-x {@code agent-v1} HTTP API. */
public final class ShieldXClient {

  private static final String CONTENT_ENCODING = "Content-Encoding";
  private static final long MAX_ERROR_BODY = 8 * 1024;
  private static final long MAX_REPORT_RESPONSE = 1 * 1024 * 1024;

  private final ShieldXConfig config;
  private final ObjectMapper mapper;
  private final HttpClient httpClient;
  private final String capabilitiesHeader;

  public ShieldXClient(ShieldXConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.mapper = config.objectMapper();
    this.httpClient =
        config.httpClient() != null
            ? config.httpClient()
            : HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    this.capabilitiesHeader = encodeCapabilities(config.capabilities());
  }

  public static Builder builder() {
    return new Builder();
  }

  public PoliciesResult fetchPolicies(long sinceRevision) {
    URI uri = endpoint("policies/v1");
    HttpRequest.Builder request = commonRequest(uri).GET().header("Accept", "application/json");
    request.header("X-Shieldx-Gateway-Id", config.gatewayId());
    if (capabilitiesHeader != null) {
      request.header("X-Shieldx-Capabilities", capabilitiesHeader);
    }
    if (sinceRevision >= 0) {
      request.header("If-None-Match", "\"rev-" + sinceRevision + "\"");
    }

    HttpResponse<InputStream> response = send(request.build());
    if (response.statusCode() == 304) {
      closeQuietly(response);
      long revision = revisionFromEtag(response.headers().firstValue("ETag").orElse(null));
      if (revision <= 0) {
        revision = sinceRevision;
      }
      return new PoliciesResult(revision, true, null);
    }
    if (response.statusCode() != 200) {
      throw apiError(response);
    }
    byte[] body = readBody(response, config.maxResponseBytes());
    PolicySnapshot snapshot = decode(body, PolicySnapshot.class, "policy snapshot");
    return new PoliciesResult(snapshot.revision(), false, snapshot);
  }

  public TemplatesResult fetchTemplates(long sinceRevision) {
    URI uri = endpoint("templates/v1");
    HttpRequest.Builder request = commonRequest(uri).GET().header("Accept", "application/json");
    request.header("X-Shieldx-Gateway-Id", config.gatewayId());
    if (sinceRevision >= 0) {
      request.header("If-None-Match", "\"rev-" + sinceRevision + "\"");
    }

    HttpResponse<InputStream> response = send(request.build());
    if (response.statusCode() == 304) {
      closeQuietly(response);
      long revision = revisionFromEtag(response.headers().firstValue("ETag").orElse(null));
      if (revision <= 0) {
        revision = sinceRevision;
      }
      return new TemplatesResult(revision, true, null);
    }
    if (response.statusCode() != 200) {
      throw apiError(response);
    }
    byte[] body = readBody(response, config.maxResponseBytes());
    TemplateSnapshot snapshot = decode(body, TemplateSnapshot.class, "template snapshot");
    return new TemplatesResult(snapshot.revision(), false, snapshot);
  }

  public ReportResult reportEvents(EventBatch batch) {
    return reportEvents(batch, ReportOptions.defaults());
  }

  public ReportResult reportEvents(EventBatch batch, ReportOptions options) {
    Objects.requireNonNull(batch, "batch");
    ReportOptions effective = options == null ? ReportOptions.defaults() : options;
    if (batch.events().size() > Limits.MAX_BATCH_EVENTS) {
      throw new BatchTooLargeException(
          "batch has " + batch.events().size() + " events, max " + Limits.MAX_BATCH_EVENTS);
    }
    if (!effective.skipValidation()) {
      for (int i = 0; i < batch.events().size(); i++) {
        batch.events().get(i).validate(i);
      }
    }

    BatchEncoder.EncodedBatch encoded =
        BatchEncoder.encode(mapper, batch, config.agentVersion(), config.gatewayId(), effective);
    HttpRequest.Builder request =
        commonRequest(endpoint("events/v1"))
            .header("Content-Type", encoded.contentType())
            .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.body()));
    if (encoded.contentEncoding() != null) {
      request.header(CONTENT_ENCODING, encoded.contentEncoding());
    }

    HttpResponse<InputStream> response = send(request.build());
    if (response.statusCode() != 202) {
      throw apiError(response);
    }
    byte[] body = readBody(response, MAX_REPORT_RESPONSE);
    ReportResponse wire = decode(body, ReportResponse.class, "report response");
    Instant serverTime = null;
    if (wire.serverTime() != null && !wire.serverTime().isBlank()) {
      try {
        serverTime = Instant.parse(wire.serverTime());
      } catch (RuntimeException ignored) {
        // A malformed server_time is advisory; accepted/rejected still count.
      }
    }
    return new ReportResult(wire.accepted(), wire.rejected(), serverTime);
  }

  private HttpRequest.Builder commonRequest(URI uri) {
    return HttpRequest.newBuilder(uri)
        .timeout(config.requestTimeout())
        .header("Authorization", "Bearer " + config.resolveToken())
        .header("User-Agent", config.userAgent())
        .header("X-Shieldx-Agent-Version", config.agentVersion());
  }

  private URI endpoint(String path) {
    return URI.create(config.baseUrl() + "/" + path);
  }

  private HttpResponse<InputStream> send(HttpRequest request) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransportException("request interrupted", e);
    } catch (IOException e) {
      throw new TransportException("request failed: " + e.getMessage(), e);
    }
  }

  private byte[] readBody(HttpResponse<InputStream> response, long maxBytes) {
    try (InputStream input = decoded(response.body(), response);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          throw new TransportException("response exceeds " + maxBytes + " bytes");
        }
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new TransportException("failed to read response: " + e.getMessage(), e);
    }
  }

  private byte[] readErrorBody(HttpResponse<InputStream> response) {
    try (InputStream input = decoded(response.body(), response)) {
      return input.readNBytes((int) MAX_ERROR_BODY);
    } catch (IOException e) {
      return new byte[0];
    }
  }

  private InputStream decoded(InputStream body, HttpResponse<?> response) throws IOException {
    InputStream input = body == null ? InputStream.nullInputStream() : body;
    String encoding = response.headers().firstValue(CONTENT_ENCODING).orElse("");
    if ("gzip".equalsIgnoreCase(encoding)) {
      return new GZIPInputStream(input);
    }
    return input;
  }

  private ShieldXException apiError(HttpResponse<InputStream> response) {
    int status = response.statusCode();
    String requestId = response.headers().firstValue("X-Request-Id").orElse("");
    byte[] body = readErrorBody(response);
    String code = "";
    String message = "";
    if (body.length > 0) {
      try {
        ErrorEnvelope envelope = mapper.readValue(body, ErrorEnvelope.class);
        if (envelope.error() != null && envelope.error().code() != null) {
          code = envelope.error().code();
          message = envelope.error().message() == null ? "" : envelope.error().message();
          if (envelope.error().requestId() != null && !envelope.error().requestId().isBlank()) {
            requestId = envelope.error().requestId();
          }
        }
      } catch (IOException ignored) {
        // Fall through to a truncated raw body.
      }
      if (code.isEmpty()) {
        message = new String(body, StandardCharsets.UTF_8).trim();
        if (message.length() > 200) {
          message = message.substring(0, 200) + "…";
        }
      }
    }
    Duration retryAfter =
        parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
    return new ShieldXException(status, code, message, requestId, retryAfter);
  }

  private <T> T decode(byte[] body, Class<T> type, String what) {
    try {
      return mapper.readValue(body, type);
    } catch (IOException e) {
      throw new TransportException("failed to decode " + what + ": " + e.getMessage(), e);
    }
  }

  private static void closeQuietly(HttpResponse<InputStream> response) {
    try {
      if (response.body() != null) {
        response.body().close();
      }
    } catch (IOException ignored) {
      // Nothing useful to do for a 304 body.
    }
  }

  private static long revisionFromEtag(String etag) {
    if (etag == null) {
      return 0;
    }
    String value = etag.trim();
    if (value.startsWith("W/")) {
      value = value.substring(2);
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    if (!value.startsWith("rev-")) {
      return 0;
    }
    try {
      long revision = Long.parseLong(value.substring(4));
      return revision < 0 ? 0 : revision;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static Duration parseRetryAfter(String value) {
    if (value == null || value.isBlank()) {
      return Duration.ZERO;
    }
    String trimmed = value.trim();
    try {
      long seconds = Long.parseLong(trimmed);
      return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO;
    } catch (NumberFormatException ignored) {
      // Try HTTP-date below.
    }
    try {
      ZonedDateTime when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration duration = Duration.between(Instant.now(), when.toInstant());
      return duration.isNegative() ? Duration.ZERO : duration;
    } catch (RuntimeException ignored) {
      return Duration.ZERO;
    }
  }

  private String encodeCapabilities(Capabilities capabilities) {
    if (capabilities == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(capabilities);
    } catch (IOException e) {
      throw new IllegalArgumentException("failed to encode capabilities", e);
    }
  }

  private record ReportResponse(
      @JsonProperty("accepted") int accepted,
      @JsonProperty("rejected") int rejected,
      @JsonProperty("server_time") String serverTime) {}

  private record ErrorEnvelope(@JsonProperty("error") ErrorBody error) {}

  private record ErrorBody(
      @JsonProperty("code") String code,
      @JsonProperty("message") String message,
      @JsonProperty("request_id") String requestId) {}

  /** Builder for {@link ShieldXClient}. */
  public static final class Builder {
    private final ShieldXConfig.Builder config = ShieldXConfig.builder();

    private Builder() {}

    public Builder baseUrl(String value) {
      config.baseUrl(value);
      return this;
    }

    public Builder token(String value) {
      config.token(value);
      return this;
    }

    public Builder tokenSupplier(Supplier<String> value) {
      config.tokenSupplier(value);
      return this;
    }

    public Builder gatewayId(String value) {
      config.gatewayId(value);
      return this;
    }

    public Builder agentVersion(String value) {
      config.agentVersion(value);
      return this;
    }

    public Builder capabilities(Capabilities value) {
      config.capabilities(value);
      return this;
    }

    public Builder httpClient(HttpClient value) {
      config.httpClient(value);
      return this;
    }

    public Builder connectTimeout(Duration value) {
      config.connectTimeout(value);
      return this;
    }

    public Builder requestTimeout(Duration value) {
      config.requestTimeout(value);
      return this;
    }

    public Builder maxResponseBytes(long value) {
      config.maxResponseBytes(value);
      return this;
    }

    public Builder userAgent(String value) {
      config.userAgent(value);
      return this;
    }

    public Builder objectMapper(ObjectMapper value) {
      config.objectMapper(value);
      return this;
    }

    public ShieldXClient build() {
      return new ShieldXClient(config.build());
    }
  }
}
