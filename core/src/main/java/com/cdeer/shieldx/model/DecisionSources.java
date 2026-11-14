package com.cdeer.shieldx.model;

import java.util.Set;

/** Contract decision sources; must agree with {@code matched_policy_id}. */
public final class DecisionSources {

  public static final String POLICY = "policy";
  public static final String NONE = "none";
  public static final String GATEWAY_ERROR = "gateway_error";

  private static final Set<String> VALID = Set.of(POLICY, NONE, GATEWAY_ERROR);

  private DecisionSources() {}

  public static boolean isValid(String value) {
    return value != null && VALID.contains(value);
  }
}
