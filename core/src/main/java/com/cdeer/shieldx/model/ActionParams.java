package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Closed action parameter set for contract v1. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActionParams(
    @JsonProperty("template_id") @JsonInclude(JsonInclude.Include.NON_NULL) String templateId,
    @JsonProperty("status_code") @JsonInclude(JsonInclude.Include.NON_NULL) Integer statusCode,
    @JsonProperty("redirect_url") @JsonInclude(JsonInclude.Include.NON_NULL) String redirectUrl,
    @JsonProperty("challenge_ttl_s") @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer challengeTtlSeconds) {}
