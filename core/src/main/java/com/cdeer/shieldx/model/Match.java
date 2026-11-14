package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Nested match group with all / any / not semantics. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Match(
    @JsonProperty("all") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Condition> all,
    @JsonProperty("any") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Condition> any,
    @JsonProperty("not") @JsonInclude(JsonInclude.Include.NON_NULL) Match not) {

  public Match {
    all = all == null ? List.of() : List.copyOf(all);
    any = any == null ? List.of() : List.copyOf(any);
  }
}
