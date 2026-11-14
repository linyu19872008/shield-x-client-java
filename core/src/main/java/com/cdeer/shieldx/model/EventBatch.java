package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Business fields of one event batch; agent_version and gateway_id are added by the client. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventBatch(
    @JsonProperty("config_revision") long configRevision,
    @JsonProperty("policy_count") int policyCount,
    @JsonProperty("client_stats") @JsonInclude(JsonInclude.Include.NON_NULL)
        ClientStats clientStats,
    @JsonProperty("events") List<Event> events) {

  public EventBatch {
    events = events == null ? List.of() : List.copyOf(events);
  }

  public static EventBatch of(List<Event> events) {
    return new EventBatch(0, 0, null, events);
  }
}
