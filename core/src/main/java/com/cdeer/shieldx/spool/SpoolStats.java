package com.cdeer.shieldx.spool;

/** Disk spool size counters. */
public record SpoolStats(int files, long bytes, int events) {

  public static SpoolStats empty() {
    return new SpoolStats(0, 0, 0);
  }
}
