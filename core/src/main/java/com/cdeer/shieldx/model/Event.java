package com.cdeer.shieldx.model;

import com.cdeer.shieldx.error.ValidationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One request event. Optional fields are omitted; matched_policy_id is always emitted. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Event(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("ts") Instant ts,
    @JsonProperty("gateway_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String gatewayId,
    @JsonProperty("client_ip") String clientIp,
    @JsonProperty("real_ip_chain") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> realIpChain,
    @JsonProperty("host") String host,
    @JsonProperty("path") String path,
    @JsonProperty("query") @JsonInclude(JsonInclude.Include.NON_EMPTY) String query,
    @JsonProperty("method") String method,
    @JsonProperty("scheme") @JsonInclude(JsonInclude.Include.NON_EMPTY) String scheme,
    @JsonProperty("ua") @JsonInclude(JsonInclude.Include.NON_EMPTY) String userAgent,
    @JsonProperty("referer") @JsonInclude(JsonInclude.Include.NON_EMPTY) String referer,
    @JsonProperty("status") int status,
    @JsonProperty("upstream_status") Integer upstreamStatus,
    @JsonProperty("duration_ms") int durationMs,
    @JsonProperty("bytes") @JsonInclude(JsonInclude.Include.NON_NULL) Long bytes,
    @JsonProperty("matched_policy_id") String matchedPolicyId,
    @JsonProperty("matched_policy_priority") @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer matchedPolicyPriority,
    @JsonProperty("config_revision") long configRevision,
    @JsonProperty("action") String action,
    @JsonProperty("action_result") String actionResult,
    @JsonProperty("decision_source") String decisionSource,
    @JsonProperty("decision_latency_us") @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer decisionLatencyUs,
    @JsonProperty("block_template_id") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String blockTemplateId,
    @JsonProperty("block_template_version") @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer blockTemplateVersion,
    @JsonProperty("headers_digest") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, String> headersDigest,
    @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String traceId,
    @JsonProperty("clock_skew_ms") @JsonInclude(JsonInclude.Include.NON_NULL) Integer clockSkewMs) {

  public Event {
    realIpChain = realIpChain == null ? null : List.copyOf(realIpChain);
    headersDigest = headersDigest == null ? null : Map.copyOf(headersDigest);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Validates this event without an index. */
  public void validate() {
    validate(-1);
  }

  /**
   * Validates this event; {@code index} is used in the error message when it is part of a batch.
   */
  public void validate(int index) {
    if (eventId == null || eventId.isEmpty() || eventId.length() > 64) {
      throw invalid(index, "event_id", "must be 1..64 characters");
    }
    if (ts == null) {
      throw invalid(index, "ts", "must not be null");
    }
    if (!isIp(clientIp)) {
      throw invalid(index, "client_ip", "not a valid IP: " + clientIp);
    }
    if (isBlank(host) || isBlank(path) || isBlank(method)) {
      throw invalid(index, "host/path/method", "must not be blank");
    }
    if (!Actions.isValid(action)) {
      throw invalid(index, "action", "invalid value: " + action);
    }
    if (!ActionResults.isValid(actionResult)) {
      throw invalid(index, "action_result", "invalid value: " + actionResult);
    }
    if (!DecisionSources.isValid(decisionSource)) {
      throw invalid(index, "decision_source", "invalid value: " + decisionSource);
    }

    if (DecisionSources.POLICY.equals(decisionSource)) {
      if (isBlank(matchedPolicyId)) {
        throw invalid(
            index, "matched_policy_id", "decision_source=policy requires a non-empty policy id");
      }
    } else if (matchedPolicyId != null && !matchedPolicyId.isEmpty()) {
      throw invalid(
          index,
          "matched_policy_id",
          "decision_source=" + decisionSource + " must not carry a policy id");
    }
  }

  private static ValidationException invalid(int index, String field, String message) {
    return new ValidationException(index, field, message);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean isIp(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    if (value.indexOf(':') >= 0) {
      try {
        return InetAddress.getByName(value) instanceof Inet6Address;
      } catch (Exception ignored) {
        return false;
      }
    }
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3) {
        return false;
      }
      for (int i = 0; i < part.length(); i++) {
        if (!Character.isDigit(part.charAt(i))) {
          return false;
        }
      }
      int octet = Integer.parseInt(part);
      if (octet > 255) {
        return false;
      }
    }
    return true;
  }

  public static final class Builder {
    private String eventId;
    private Instant ts;
    private String gatewayId;
    private String clientIp;
    private List<String> realIpChain;
    private String host;
    private String path;
    private String query;
    private String method;
    private String scheme;
    private String userAgent;
    private String referer;
    private int status;
    private Integer upstreamStatus;
    private int durationMs;
    private Long bytes;
    private String matchedPolicyId;
    private Integer matchedPolicyPriority;
    private long configRevision;
    private String action;
    private String actionResult;
    private String decisionSource;
    private Integer decisionLatencyUs;
    private String blockTemplateId;
    private Integer blockTemplateVersion;
    private Map<String, String> headersDigest;
    private String traceId;
    private Integer clockSkewMs;

    private Builder() {}

    public Builder eventId(String value) {
      this.eventId = value;
      return this;
    }

    public Builder ts(Instant value) {
      this.ts = value;
      return this;
    }

    public Builder gatewayId(String value) {
      this.gatewayId = value;
      return this;
    }

    public Builder clientIp(String value) {
      this.clientIp = value;
      return this;
    }

    public Builder realIpChain(List<String> value) {
      this.realIpChain = value;
      return this;
    }

    public Builder host(String value) {
      this.host = value;
      return this;
    }

    public Builder path(String value) {
      this.path = value;
      return this;
    }

    public Builder query(String value) {
      this.query = value;
      return this;
    }

    public Builder method(String value) {
      this.method = value;
      return this;
    }

    public Builder scheme(String value) {
      this.scheme = value;
      return this;
    }

    public Builder userAgent(String value) {
      this.userAgent = value;
      return this;
    }

    public Builder referer(String value) {
      this.referer = value;
      return this;
    }

    public Builder status(int value) {
      this.status = value;
      return this;
    }

    public Builder upstreamStatus(Integer value) {
      this.upstreamStatus = value;
      return this;
    }

    public Builder durationMs(int value) {
      this.durationMs = value;
      return this;
    }

    public Builder bytes(Long value) {
      this.bytes = value;
      return this;
    }

    public Builder matchedPolicyId(String value) {
      this.matchedPolicyId = value;
      return this;
    }

    public Builder matchedPolicyPriority(Integer value) {
      this.matchedPolicyPriority = value;
      return this;
    }

    public Builder configRevision(long value) {
      this.configRevision = value;
      return this;
    }

    public Builder action(String value) {
      this.action = value;
      return this;
    }

    public Builder actionResult(String value) {
      this.actionResult = value;
      return this;
    }

    public Builder decisionSource(String value) {
      this.decisionSource = value;
      return this;
    }

    public Builder decisionLatencyUs(Integer value) {
      this.decisionLatencyUs = value;
      return this;
    }

    public Builder blockTemplateId(String value) {
      this.blockTemplateId = value;
      return this;
    }

    public Builder blockTemplateVersion(Integer value) {
      this.blockTemplateVersion = value;
      return this;
    }

    public Builder headersDigest(Map<String, String> value) {
      this.headersDigest = value;
      return this;
    }

    public Builder traceId(String value) {
      this.traceId = value;
      return this;
    }

    public Builder clockSkewMs(Integer value) {
      this.clockSkewMs = value;
      return this;
    }

    public Event build() {
      return new Event(
          eventId,
          ts,
          gatewayId,
          clientIp,
          realIpChain,
          host,
          path,
          query,
          method,
          scheme,
          userAgent,
          referer,
          status,
          upstreamStatus,
          durationMs,
          bytes,
          matchedPolicyId,
          matchedPolicyPriority,
          configRevision,
          action,
          actionResult,
          decisionSource,
          decisionLatencyUs,
          blockTemplateId,
          blockTemplateVersion,
          headersDigest,
          traceId,
          clockSkewMs);
    }
  }
}
