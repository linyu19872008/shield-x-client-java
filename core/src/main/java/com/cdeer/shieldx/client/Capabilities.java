package com.cdeer.shieldx.client;

import com.cdeer.shieldx.model.Actions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Gateway capabilities sent with policy pulls. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Capabilities(
    @JsonProperty("actions") List<String> actions,
    @JsonProperty("match_fields") List<String> matchFields,
    @JsonProperty("block_templates") boolean blockTemplates,
    @JsonProperty("template_spec_version") int templateSpecVersion,
    @JsonProperty("max_rules") int maxRules,
    @JsonProperty("supports_rate_limit") boolean supportsRateLimit,
    @JsonProperty("delta_snapshot") boolean deltaSnapshot,
    @JsonProperty("apply_ack") boolean applyAck) {

  public Capabilities {
    actions = actions == null ? List.of() : List.copyOf(actions);
    matchFields = matchFields == null ? List.of() : List.copyOf(matchFields);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Validates the capability declaration before it is sent. */
  public void validate() {
    if (actions.isEmpty()) {
      throw new IllegalArgumentException("capabilities.actions must not be empty");
    }
    Set<String> seenActions = new HashSet<>();
    for (String action : actions) {
      if (!Actions.isValid(action) || Actions.NONE.equals(action)) {
        throw new IllegalArgumentException(
            "capabilities.actions contains invalid action: " + action);
      }
      if (!seenActions.add(action)) {
        throw new IllegalArgumentException("capabilities.actions contains duplicate: " + action);
      }
    }
    if (matchFields.isEmpty()) {
      throw new IllegalArgumentException("capabilities.match_fields must not be empty");
    }
    for (String field : matchFields) {
      if (field == null || field.isBlank()) {
        throw new IllegalArgumentException("capabilities.match_fields contains a blank field");
      }
    }
    if (maxRules <= 0) {
      throw new IllegalArgumentException("capabilities.max_rules must be positive");
    }
    if (templateSpecVersion < 0) {
      throw new IllegalArgumentException("capabilities.template_spec_version must not be negative");
    }
    if (blockTemplates && templateSpecVersion != 1) {
      throw new IllegalArgumentException(
          "template_spec_version must be 1 when block_templates is true");
    }
  }

  public static final class Builder {
    private final List<String> actions = new ArrayList<>();
    private final List<String> matchFields = new ArrayList<>();
    private boolean blockTemplates;
    private int templateSpecVersion;
    private int maxRules;
    private boolean supportsRateLimit;
    private boolean deltaSnapshot;
    private boolean applyAck;

    private Builder() {}

    public Builder actions(String... values) {
      actions.clear();
      actions.addAll(List.of(values));
      return this;
    }

    public Builder actions(List<String> values) {
      actions.clear();
      actions.addAll(Objects.requireNonNull(values, "actions"));
      return this;
    }

    public Builder matchFields(String... values) {
      matchFields.clear();
      matchFields.addAll(List.of(values));
      return this;
    }

    public Builder matchFields(List<String> values) {
      matchFields.clear();
      matchFields.addAll(Objects.requireNonNull(values, "matchFields"));
      return this;
    }

    public Builder blockTemplates(boolean value) {
      this.blockTemplates = value;
      return this;
    }

    public Builder templateSpecVersion(int value) {
      this.templateSpecVersion = value;
      return this;
    }

    public Builder maxRules(int value) {
      this.maxRules = value;
      return this;
    }

    public Builder supportsRateLimit(boolean value) {
      this.supportsRateLimit = value;
      return this;
    }

    public Builder deltaSnapshot(boolean value) {
      this.deltaSnapshot = value;
      return this;
    }

    public Builder applyAck(boolean value) {
      this.applyAck = value;
      return this;
    }

    public Capabilities build() {
      Capabilities capabilities =
          new Capabilities(
              actions,
              matchFields,
              blockTemplates,
              templateSpecVersion,
              maxRules,
              supportsRateLimit,
              deltaSnapshot,
              applyAck);
      capabilities.validate();
      return capabilities;
    }
  }
}
