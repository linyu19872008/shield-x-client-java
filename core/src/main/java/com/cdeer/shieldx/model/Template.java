package com.cdeer.shieldx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** A block template served by shield-x. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Template(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("version") int version,
    @JsonProperty("checksum") String checksum,
    @JsonProperty("content_type") String contentType,
    @JsonProperty("status_code") int statusCode,
    @JsonProperty("headers") Map<String, String> headers,
    @JsonProperty("content") String content,
    @JsonProperty("vars") Map<String, String> vars) {

  public Template {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    vars = vars == null ? Map.of() : Map.copyOf(vars);
  }
}
