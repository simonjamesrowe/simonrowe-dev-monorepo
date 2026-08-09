package com.simonrowe.factory.feedback.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewConversationTest {

  private static ConversationComment human(final String body) {
    return new ConversationComment("simon", false, body, null, null, null, "https://c/1");
  }

  private static ConversationComment bot(final String body) {
    return new ConversationComment(
        "simonrowe-code-reviewer", true, body, "src/App.java", 12, "@@ hunk", "https://c/2");
  }

  @Test
  void botOnlyConversationHasNoHumanSignal() {
    ReviewConversation conversation =
        new ReviewConversation(
            "Title", "https://pr/1", "author", true,
            List.of(new ConversationReview(
                "simonrowe-code-reviewer", true, "COMMENTED", "summary", "https://r/1")),
            List.of(new ConversationThread(false, List.of(bot("finding")))),
            List.of());

    assertThat(conversation.hasHumanSignal()).isFalse();
  }

  @Test
  void humanReplyInsideBotThreadCountsAsSignal() {
    ReviewConversation conversation =
        new ReviewConversation(
            "Title", "https://pr/1", "author", true,
            List.of(),
            List.of(new ConversationThread(true, List.of(bot("finding"), human("agreed, fixed")))),
            List.of());

    assertThat(conversation.hasHumanSignal()).isTrue();
  }

  @Test
  void humanIssueCommentCountsAsSignal() {
    ReviewConversation conversation =
        new ReviewConversation(
            "Title", "https://pr/1", "author", false,
            List.of(), List.of(), List.of(human("please stop doing X")));

    assertThat(conversation.hasHumanSignal()).isTrue();
  }
}
