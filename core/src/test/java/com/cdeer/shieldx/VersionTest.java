package com.cdeer.shieldx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionTest {

  @Test
  void versionIsNeverBlank() {
    assertThat(Version.version()).isNotBlank();
  }
}
