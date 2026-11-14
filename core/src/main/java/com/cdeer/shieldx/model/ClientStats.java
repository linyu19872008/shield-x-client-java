package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Lets the server see whether the reporter is dropping or spooling events. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientStats(
    @JsonProperty("queue_depth") int queueDepth,
    @JsonProperty("dropped_total") long droppedTotal,
    @JsonProperty("spool_size") int spoolSize) {}
