package com.cdeer.shieldx.client;

import com.cdeer.shieldx.Version;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Immutable client configuration. Build with {@link #builder()}. */
public final class ShieldXConfig {

  private static final Pattern GATEWAY_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final long DEFAULT_MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

  private final String baseUrl;
  private final Supplier<String> tokenSupplier;
  private final String gatewayId;
  private final String agentVersion;
  private final Capabilities capabilities;
  private final HttpClient httpClient;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final long maxResponseBytes;
  private final String userAgent;
  private final ObjectMapper objectMapper;
  private final PrivateKey signingKey;

  private ShieldXConfig(Builder builder) {
    this.baseUrl = normalizeBaseUrl(builder.baseUrl);
    this.gatewayId = requireText(builder.gatewayId, "gatewayId");
    if (!GATEWAY_ID.matcher(gatewayId).matches()) {
      throw new IllegalArgumentException("gatewayId must be 1..64 characters of [A-Za-z0-9._-]");
    }
    this.agentVersion = requireText(builder.agentVersion, "agentVersion");
    this.capabilities = builder.capabilities;
    if (capabilities != null) {
      capabilities.validate();
    }
    this.httpClient = builder.httpClient;
    this.connectTimeout = positive(builder.connectTimeout, "connectTimeout");
    this.requestTimeout = positive(builder.requestTimeout, "requestTimeout");
    this.maxResponseBytes = builder.maxResponseBytes;
    if (maxResponseBytes <= 0) {
      throw new IllegalArgumentException("maxResponseBytes must be positive");
    }
    this.userAgent =
        builder.userAgent == null || builder.userAgent.isBlank()
            ? "shield-x-client-java/" + Version.version()
            : builder.userAgent;
    this.objectMapper = builder.objectMapper == null ? defaultObjectMapper() : builder.objectMapper;
    this.signingKey = parseSigningKey(builder.signingKey);

    if (builder.token != null && builder.tokenSupplier != null) {
      throw new IllegalArgumentException("token and tokenSupplier are mutually exclusive");
    }
    if (builder.token != null) {
      String token = builder.token.trim();
      if (token.isEmpty()) {
        throw new IllegalArgumentException("token must not be blank");
      }
      this.tokenSupplier = () -> token;
    } else if (builder.tokenSupplier != null) {
      this.tokenSupplier = builder.tokenSupplier;
    } else {
      throw new IllegalArgumentException("token or tokenSupplier is required");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Default mapper used by the SDK. Callers may inject their own mapper via the builder. */
  public static ObjectMapper defaultObjectMapper() {
    return JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  public String baseUrl() {
    return baseUrl;
  }

  public String gatewayId() {
    return gatewayId;
  }

  public String agentVersion() {
    return agentVersion;
  }

  public Capabilities capabilities() {
    return capabilities;
  }

  public HttpClient httpClient() {
    return httpClient;
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public long maxResponseBytes() {
    return maxResponseBytes;
  }

  public String userAgent() {
    return userAgent;
  }

  public ObjectMapper objectMapper() {
    return objectMapper;
  }

  public PrivateKey signingKey() {
    return signingKey;
  }

  private static PrivateKey parseSigningKey(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    byte[] seed;
    try {
      seed = HexFormat.of().parseHex(value.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("signingKey must be 32-byte Ed25519 seed hex", e);
    }
    if (seed.length != 32) {
      throw new IllegalArgumentException("signingKey must be 32-byte Ed25519 seed hex");
    }
    try {
      return KeyFactory.getInstance("Ed25519")
          .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
    } catch (Exception e) {
      throw new IllegalArgumentException("failed to load Ed25519 signing key", e);
    }
  }

  String resolveToken() {
    String token = tokenSupplier.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("token supplier returned a blank token");
    }
    return token;
  }

  private static String normalizeBaseUrl(String value) {
    String raw = requireText(value, "baseUrl").trim();
    while (raw.endsWith("/")) {
      raw = raw.substring(0, raw.length() - 1);
    }
    final URI uri;
    try {
      uri = URI.create(raw);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("baseUrl is not a valid URI", e);
    }
    if (!uri.isAbsolute() || uri.getHost() == null) {
      throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL");
    }
    String scheme = uri.getScheme().toLowerCase();
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("baseUrl must use http or https");
    }
    if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException("baseUrl must not contain userinfo, query or fragment");
    }
    String path = uri.getPath();
    if (path == null || !path.endsWith("/shieldx")) {
      throw new IllegalArgumentException("baseUrl must include the /shieldx path prefix");
    }
    return raw;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static Duration positive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  public static final class Builder {
    private String baseUrl;
    private String token;
    private Supplier<String> tokenSupplier;
    private String gatewayId;
    private String agentVersion;
    private Capabilities capabilities;
    private HttpClient httpClient;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    private String userAgent;
    private ObjectMapper objectMapper;
    private String signingKey;

    private Builder() {}

    public Builder baseUrl(String value) {
      this.baseUrl = value;
      return this;
    }

    public Builder token(String value) {
      this.token = value;
      return this;
    }

    public Builder tokenSupplier(Supplier<String> value) {
      this.tokenSupplier = Objects.requireNonNull(value, "tokenSupplier");
      return this;
    }

    public Builder gatewayId(String value) {
      this.gatewayId = value;
      return this;
    }

    public Builder agentVersion(String value) {
      this.agentVersion = value;
      return this;
    }

    public Builder capabilities(Capabilities value) {
      this.capabilities = value;
      return this;
    }

    public Builder httpClient(HttpClient value) {
      this.httpClient = value;
      return this;
    }

    public Builder connectTimeout(Duration value) {
      this.connectTimeout = value;
      return this;
    }

    public Builder requestTimeout(Duration value) {
      this.requestTimeout = value;
      return this;
    }

    public Builder maxResponseBytes(long value) {
      this.maxResponseBytes = value;
      return this;
    }

    public Builder userAgent(String value) {
      this.userAgent = value;
      return this;
    }

    public Builder objectMapper(ObjectMapper value) {
      this.objectMapper = value;
      return this;
    }

    public Builder signingKey(String value) {
      this.signingKey = value;
      return this;
    }

    public ShieldXConfig build() {
      return new ShieldXConfig(this);
    }
  }
}
