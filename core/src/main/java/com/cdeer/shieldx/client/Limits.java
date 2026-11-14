package com.cdeer.shieldx.client;

/** Hard limits from contract {@code agent-v1}. */
public final class Limits {

  public static final int MAX_BATCH_EVENTS = 1000;
  public static final int MAX_BATCH_BYTES = 1 << 20;

  private Limits() {}
}
