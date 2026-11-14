package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Full policy snapshot. The receiver must replace, never merge, including an empty list. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicySnapshot(
    @JsonProperty("revision") long revision,
    @JsonProperty("generated_at") Instant generatedAt,
    @JsonProperty("policy_count") int policyCount,
    @JsonProperty("filtered_out") int filteredOut,
    @JsonProperty("policies") List<Policy> policies) {

  public PolicySnapshot {
    policies = policies == null ? List.of() : List.copyOf(policies);
  }
}
