package com.cdeer.shieldx.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void passesAllConformanceVectors() throws Exception {
    JsonNode cases = resource("/template/cases.json");
    assertThat(cases).isNotNull();
    assertThat(cases.size()).isEqualTo(14);

    for (JsonNode testCase : cases) {
      String id = testCase.path("id").asText();
      String template = templateOf(testCase);
      Map<String, String> vars = varsOf(testCase);

      if (testCase.has("expect_error")) {
        assertThatThrownBy(() -> TemplateRenderer.render(template, vars))
            .as(id)
            .isInstanceOf(TemplateRenderException.class)
            .satisfies(
                e ->
                    assertThat(((TemplateRenderException) e).code())
                        .isEqualTo(testCase.path("expect_error").asText()));
      } else {
        assertThat(TemplateRenderer.render(template, vars))
            .as(id)
            .isEqualTo(testCase.path("expected").asText());
      }
    }
  }

  @Test
  void unknownPlaceholderIsPreservedWithWhitespace() throws Exception {
    assertThat(TemplateRenderer.render("<p>{{ unknown }}</p>", Map.of()))
        .isEqualTo("<p>{{ unknown }}</p>");
  }

  @Test
  void missingVariableBecomesEmpty() throws Exception {
    assertThat(TemplateRenderer.render("<p>{{ request_id }}</p>", Map.of())).isEqualTo("<p></p>");
  }

  @Test
  void bomIsRemovedBeforeRendering() throws Exception {
    assertThat(TemplateRenderer.render("\uFEFF<p>{{ host }}</p>", Map.of("host", "a&b")))
        .isEqualTo("<p>a&amp;b</p>");
  }

  @Test
  void oversizedTemplateIsRejected() {
    String template = "x".repeat(TemplateRenderer.MAX_TEMPLATE_BYTES + 1);
    assertThatThrownBy(() -> TemplateRenderer.render(template, Map.of()))
        .isInstanceOf(TemplateRenderException.class)
        .satisfies(
            e ->
                assertThat(((TemplateRenderException) e).code())
                    .isEqualTo(TemplateRenderer.TEMPLATE_TOO_LARGE));
  }

  private static String templateOf(JsonNode testCase) {
    if (testCase.has("template")) {
      return testCase.path("template").asText();
    }
    JsonNode repeat = testCase.path("template_repeat");
    return repeat.path("text").asText().repeat(repeat.path("times").asInt());
  }

  private static Map<String, String> varsOf(JsonNode testCase) {
    Map<String, String> vars = new HashMap<>();
    JsonNode node = testCase.path("vars");
    node.fields().forEachRemaining(entry -> vars.put(entry.getKey(), entry.getValue().asText()));
    return vars;
  }

  private JsonNode resource(String name) throws IOException {
    try (InputStream input = getClass().getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException("missing resource " + name);
      }
      return MAPPER.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
