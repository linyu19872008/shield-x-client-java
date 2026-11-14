package com.cdeer.shieldx.error;

import java.time.Duration;
import java.util.Objects;

/** Structured error returned by shield-x, or a protocol error detected by the SDK. */
public class ShieldXException extends RuntimeException {

  private final int statusCode;
  private final String code;
  private final String requestId;
  private final Duration retryAfter;

  public ShieldXException(
      int statusCode, String code, String message, String requestId, Duration retryAfter) {
    this(statusCode, code, message, requestId, retryAfter, null);
  }

  public ShieldXException(
      int statusCode,
      String code,
      String message,
      String requestId,
      Duration retryAfter,
      Throwable cause) {
    super(Objects.requireNonNullElse(message, ""), cause);
    this.statusCode = statusCode;
    this.code = Objects.requireNonNullElse(code, "");
    this.requestId = Objects.requireNonNullElse(requestId, "");
    this.retryAfter = retryAfter == null ? Duration.ZERO : retryAfter;
  }

  public int statusCode() {
    return statusCode;
  }

  public String code() {
    return code;
  }

  public String requestId() {
    return requestId;
  }

  public Duration retryAfter() {
    return retryAfter;
  }

  /** True for 429 and 5xx responses. Transport errors override this to true. */
  public boolean retryable() {
    return statusCode == 429 || statusCode >= 500;
  }

  public boolean authentication() {
    return statusCode == 401 || statusCode == 403;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("ShieldXException{status=").append(statusCode);
    if (!code.isEmpty()) {
      sb.append(", code=").append(code);
    }
    if (!requestId.isEmpty()) {
      sb.append(", requestId=").append(requestId);
    }
    if (!getMessage().isEmpty()) {
      sb.append(", message=").append(getMessage());
    }
    return sb.append('}').toString();
  }
}
