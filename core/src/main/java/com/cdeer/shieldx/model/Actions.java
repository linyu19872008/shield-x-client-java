package com.cdeer.shieldx.model;

import java.util.Set;

/** Contract action types. Kept as strings so unknown future values remain decodable. */
public final class Actions {

  public static final String OBSERVE = "observe";
  public static final String BLOCK = "block";
  public static final String REDIRECT = "redirect";
  public static final String JAVASCRIPT_CHALLENGE = "javascript_challenge";
  public static final String NONE = "none";

  private static final Set<String> VALID =
      Set.of(OBSERVE, BLOCK, REDIRECT, JAVASCRIPT_CHALLENGE, NONE);

  private Actions() {}

  public static boolean isValid(String value) {
    return value != null && VALID.contains(value);
  }
}
