package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** What a policy does when it matches. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Action(
    @JsonProperty("type") String type,
    @JsonProperty("params") @JsonInclude(JsonInclude.Include.NON_NULL) ActionParams params) {}
