package com.simonrowe.factory.feedback.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FeedbackPrGatewayTest {

  @Test
  void buildsPullRequestPayload() {
    JsonNode payload =
        FeedbackPrGateway.pullRequestPayload(
            new ObjectMapper(),
            "feedback/simonrowe-dev-monorepo-pr-42",
            "main",
            "docs: apply review lessons from simonrowe-dev-monorepo#42",
            "Body");

    assertThat(payload.path("head").asText()).isEqualTo("feedback/simonrowe-dev-monorepo-pr-42");
    assertThat(payload.path("base").asText()).isEqualTo("main");
    assertThat(payload.path("title").asText()).startsWith("docs:");
    assertThat(payload.path("body").asText()).isEqualTo("Body");
  }
}
