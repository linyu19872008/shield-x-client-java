package com.cdeer.shieldx.error;

/** The batch exceeds the contract limit of 1000 events or 1 MiB before compression. */
public class BatchTooLargeException extends IllegalArgumentException {

  public BatchTooLargeException(String message) {
    super(message);
  }
}
