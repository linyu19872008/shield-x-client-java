package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** One match condition. {@code value} stays as raw JSON to preserve unknown shapes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Condition(
    @JsonProperty("field") String field,
    @JsonProperty("name") @JsonInclude(JsonInclude.Include.NON_NULL) String name,
    @JsonProperty("op") String op,
    @JsonProperty("value") JsonNode value) {}
