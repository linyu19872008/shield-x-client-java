package com.cdeer.shieldx.model;

import java.util.Set;

/** Contract action results. */
public final class ActionResults {

  public static final String EXECUTED = "executed";
  public static final String CHALLENGED = "challenged";
  public static final String PASSED = "passed";
  public static final String FAILED = "failed";
  public static final String SKIPPED = "skipped";

  private static final Set<String> VALID = Set.of(EXECUTED, CHALLENGED, PASSED, FAILED, SKIPPED);

  private ActionResults() {}

  public static boolean isValid(String value) {
    return value != null && VALID.contains(value);
  }
}
