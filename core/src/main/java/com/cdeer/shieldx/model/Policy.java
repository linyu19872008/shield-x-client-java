package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A single policy. Order is server-defined and must not be sorted by the client. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Policy(
    @JsonProperty("id") String id,
    @JsonProperty("version") int version,
    @JsonProperty("name") String name,
    @JsonProperty("priority") int priority,
    @JsonProperty("match") Match match,
    @JsonProperty("action") Action action) {

  public Policy {
    if (match == null) {
      match = new Match(List.of(), List.of(), null);
    }
  }
}
