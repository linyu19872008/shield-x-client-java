package com.cdeer.shieldx.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cdeer.shieldx.client.ShieldXConfig;
import com.cdeer.shieldx.error.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventValidationTest {

  private static final Instant NOW = Instant.parse("2026-11-14T16:00:00Z");

  @Test
  void validPolicyEventPasses() {
    assertThatCode(() -> policyEvent().validate()).doesNotThrowAnyException();
  }

  @Test
  void policyDecisionRequiresMatchedPolicyId() {
    Event event =
        Event.builder()
            .eventId("evt-1")
            .ts(NOW)
            .clientIp("203.0.113.10")
            .host("example.com")
            .path("/")
            .method("GET")
            .status(403)
            .durationMs(1)
            .action("block")
            .actionResult("executed")
            .decisionSource("policy")
            .build();

    assertThatThrownBy(event::validate)
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("matched_policy_id");
  }

  @Test
  void nonPolicyDecisionMustNotCarryPolicyId() {
    Event event =
        Event.builder()
            .eventId("evt-1")
            .ts(NOW)
            .clientIp("203.0.113.10")
            .host("example.com")
            .path("/")
            .method("GET")
            .status(200)
            .durationMs(1)
            .matchedPolicyId("pol_01")
            .action("observe")
            .actionResult("passed")
            .decisionSource("none")
            .build();

    assertThatThrownBy(event::validate)
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("matched_policy_id");
  }

  @Test
  void invalidIpIsRejected() {
    Event event =
        Event.builder()
            .eventId("evt-1")
            .ts(NOW)
            .clientIp("not-an-ip")
            .host("example.com")
            .path("/")
            .method("GET")
            .status(200)
            .durationMs(1)
            .action("observe")
            .actionResult("passed")
            .decisionSource("none")
            .build();

    assertThatThrownBy(event::validate)
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("client_ip");
  }

  @Test
  void ipv6IsAccepted() {
    Event event =
        Event.builder()
            .eventId("evt-1")
            .ts(NOW)
            .clientIp("2001:db8::1")
            .host("example.com")
            .path("/")
            .method("GET")
            .status(200)
            .durationMs(1)
            .action("observe")
            .actionResult("passed")
            .decisionSource("none")
            .build();

    assertThatCode(event::validate).doesNotThrowAnyException();
  }

  @Test
  void matchedPolicyIdIsSerializedAsNull() throws Exception {
    Event event =
        Event.builder()
            .eventId("evt-1")
            .ts(NOW)
            .clientIp("203.0.113.10")
            .host("example.com")
            .path("/")
            .method("GET")
            .status(200)
            .durationMs(1)
            .action("observe")
            .actionResult("passed")
            .decisionSource("none")
            .build();

    ObjectMapper mapper = ShieldXConfig.defaultObjectMapper();
    JsonNode node = mapper.readTree(mapper.writeValueAsBytes(event));
    assertThat(node.has("matched_policy_id")).isTrue();
    assertThat(node.get("matched_policy_id").isNull()).isTrue();
  }

  @Test
  void unknownFieldsAreIgnored() throws Exception {
    String json =
        "{\"revision\":1,\"generated_at\":\"2026-11-14T00:00:00Z\",\"policy_count\":1,"
            + "\"filtered_out\":0,\"future\":\"x\",\"policies\":[{\"id\":\"p1\",\"version\":1,"
            + "\"name\":\"n\",\"priority\":1,\"match\":{},\"action\":{\"type\":\"observe\"},"
            + "\"future_field\":true}]}";
    PolicySnapshot snapshot =
        ShieldXConfig.defaultObjectMapper().readValue(json, PolicySnapshot.class);
    assertThat(snapshot.policies()).hasSize(1);
    assertThat(snapshot.policies().get(0).id()).isEqualTo("p1");
  }

  private static Event policyEvent() {
    return Event.builder()
        .eventId("evt-1")
        .ts(NOW)
        .clientIp("203.0.113.10")
        .host("example.com")
        .path("/")
        .method("GET")
        .status(403)
        .durationMs(1)
        .matchedPolicyId("pol_01")
        .action("block")
        .actionResult("executed")
        .decisionSource("policy")
        .build();
  }
}
