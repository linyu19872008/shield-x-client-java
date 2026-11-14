package com.cdeer.shieldx.client;

import java.util.Objects;

/** Per-call event reporting options. Defaults match the contract: NDJSON + gzip. */
public record ReportOptions(Format format, boolean gzip, boolean skipValidation) {

  public ReportOptions {
    Objects.requireNonNull(format, "format");
  }

  public static ReportOptions defaults() {
    return new ReportOptions(Format.NDJSON, true, false);
  }

  public static ReportOptions json() {
    return new ReportOptions(Format.JSON, true, false);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Format format = Format.NDJSON;
    private boolean gzip = true;
    private boolean skipValidation;

    private Builder() {}

    public Builder format(Format value) {
      this.format = Objects.requireNonNull(value, "format");
      return this;
    }

    public Builder gzip(boolean value) {
      this.gzip = value;
      return this;
    }

    public Builder skipValidation(boolean value) {
      this.skipValidation = value;
      return this;
    }

    public ReportOptions build() {
      return new ReportOptions(format, gzip, skipValidation);
    }
  }
}
