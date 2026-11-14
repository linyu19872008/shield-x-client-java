package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Full template snapshot. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateSnapshot(
    @JsonProperty("revision") long revision, @JsonProperty("templates") List<Template> templates) {

  public TemplateSnapshot {
    templates = templates == null ? List.of() : List.copyOf(templates);
  }
}
