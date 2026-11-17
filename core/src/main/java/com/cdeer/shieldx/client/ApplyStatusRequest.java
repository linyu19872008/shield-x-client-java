package com.cdeer.shieldx.client;

/**
 * Explicit ACK/NACK payload. Gateway id, agent version, caps hash and timestamp are filled by the
 * client.
 */
public record ApplyStatusRequest(
    long appliedRevision,
    String status,
    int policyCount,
    int templateCount,
    int durationMs,
    String errorCode,
    String errorDetail) {

  public static ApplyStatusRequest applied(long revision, int policyCount) {
    return new ApplyStatusRequest(revision, "applied", policyCount, 0, 0, null, null);
  }

  public static ApplyStatusRequest failed(long revision, String errorCode, String errorDetail) {
    return new ApplyStatusRequest(revision, "failed", 0, 0, 0, errorCode, errorDetail);
  }
}
