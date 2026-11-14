package com.cdeer.shieldx.client;

/** Request body format for event reporting. */
public enum Format {
  JSON("json", "application/json"),
  NDJSON("ndjson", "application/x-ndjson");

  private final String wireName;
  private final String contentType;

  Format(String wireName, String contentType) {
    this.wireName = wireName;
    this.contentType = contentType;
  }

  public String wireName() {
    return wireName;
  }

  public String contentType() {
    return contentType;
  }
}
