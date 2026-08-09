package com.simonrowe.factory.feedback.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.feedback.domain.ReviewConversation;
import org.junit.jupiter.api.Test;

class ConversationGatewayTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void mapsReviewsThreadsAndCommentsWithBotDetection() throws Exception {
    JsonNode pullRequest =
        objectMapper.readTree(
            """
            {
              "title": "feat: add thing",
              "url": "https://github.com/example/project/pull/42",
              "merged": true,
              "author": {"login": "simonjamesrowe", "__typename": "User"},
              "reviews": {"nodes": [
                {"author": {"login": "simonrowe-code-reviewer", "__typename": "Bot"},
                 "state": "COMMENTED", "body": "summary", "url": "https://r/1"}
              ]},
              "reviewThreads": {"nodes": [
                {"isResolved": true, "comments": {"nodes": [
                  {"author": {"login": "simonrowe-code-reviewer", "__typename": "Bot"},
                   "body": "finding", "path": "src/App.java", "line": 12,
                   "diffHunk": "@@ hunk", "url": "https://c/1"},
                  {"author": {"login": "simonjamesrowe", "__typename": "User"},
                   "body": "fixed", "path": "src/App.java", "line": 12,
                   "diffHunk": "@@ hunk", "url": "https://c/2"}
                ]}}
              ]},
              "comments": {"nodes": [
                {"author": null, "body": "drive-by", "url": "https://c/3"}
              ]}
            }
            """);

    ReviewConversation conversation = ConversationGateway.toConversation(pullRequest);

    assertThat(conversation.title()).isEqualTo("feat: add thing");
    assertThat(conversation.merged()).isTrue();
    assertThat(conversation.author()).isEqualTo("simonjamesrowe");
    assertThat(conversation.reviews()).hasSize(1);
    assertThat(conversation.reviews().getFirst().bot()).isTrue();
    assertThat(conversation.threads()).hasSize(1);
    assertThat(conversation.threads().getFirst().resolved()).isTrue();
    assertThat(conversation.threads().getFirst().comments().get(1).bot()).isFalse();
    assertThat(conversation.threads().getFirst().comments().getFirst().line()).isEqualTo(12);
    assertThat(conversation.issueComments().getFirst().author()).isEqualTo("ghost");
    assertThat(conversation.hasHumanSignal()).isTrue();
  }
}
