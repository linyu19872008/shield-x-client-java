package com.cdeer.shieldx.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CapabilitiesTest {

  @Test
  void validCapabilitiesBuild() {
    Capabilities capabilities =
        Capabilities.builder()
            .actions("observe", "block")
            .matchFields("ip", "path")
            .blockTemplates(true)
            .templateSpecVersion(1)
            .maxRules(5000)
            .supportsRateLimit(true)
            .build();

    assertThat(capabilities.actions()).containsExactly("observe", "block");
    assertThat(capabilities.maxRules()).isEqualTo(5000);
  }

  @Test
  void actionsMustNotBeEmpty() {
    assertThatThrownBy(() -> Capabilities.builder().matchFields("ip").maxRules(1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actions");
  }

  @Test
  void noneIsNotAllowedInCapabilities() {
    assertThatThrownBy(
            () -> Capabilities.builder().actions("none").matchFields("ip").maxRules(1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid action");
  }

  @Test
  void duplicateActionsAreRejected() {
    assertThatThrownBy(
            () ->
                Capabilities.builder()
                    .actions("observe", "observe")
                    .matchFields("ip")
                    .maxRules(1)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void blockTemplatesRequiresTemplateSpecVersionOne() {
    assertThatThrownBy(
            () ->
                Capabilities.builder()
                    .actions("block")
                    .matchFields("ip")
                    .blockTemplates(true)
                    .templateSpecVersion(2)
                    .maxRules(1)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("template_spec_version");
  }
}
