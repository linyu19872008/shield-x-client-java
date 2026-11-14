package com.cdeer.shieldx;

/** Runtime version information for the SDK. */
public final class Version {

  private Version() {}

  /**
   * Returns the implementation version from the JAR manifest, or {@code "dev"} when running from an
   * unpackaged classpath (for example, tests or an IDE).
   */
  public static String version() {
    String version = Version.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "dev" : version;
  }
}
