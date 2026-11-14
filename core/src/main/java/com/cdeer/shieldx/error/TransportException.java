package com.cdeer.shieldx.error;

import java.time.Duration;

/** Network, timeout, TLS, DNS or response-size failure. Always retryable by policy. */
public class TransportException extends ShieldXException {

  public TransportException(String message) {
    super(0, "TRANSPORT", message, "", Duration.ZERO);
  }

  public TransportException(String message, Throwable cause) {
    super(0, "TRANSPORT", message, "", Duration.ZERO, cause);
  }

  @Override
  public boolean retryable() {
    return true;
  }
}
