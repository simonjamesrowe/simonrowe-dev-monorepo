package com.simonrowe.factory.feedback.domain;

import java.util.List;

/** Complete PR conversation: reviews, code-review threads, and issue comments. */
public record ReviewConversation(
    String title, String url, String author, boolean merged,
    List<ConversationReview> reviews, List<ConversationThread> threads,
    List<ConversationComment> issueComments) {

  public ReviewConversation {
    reviews = reviews == null ? List.of() : List.copyOf(reviews);
    threads = threads == null ? List.of() : List.copyOf(threads);
    issueComments = issueComments == null ? List.of() : List.copyOf(issueComments);
  }

  /** True if any human (not bot) has left a review, thread comment, or issue comment. */
  public boolean hasHumanSignal() {
    boolean humanReview =
        reviews.stream().anyMatch(review -> !review.bot() && !review.body().isBlank());
    boolean humanThreadComment =
        threads.stream()
            .flatMap(thread -> thread.comments().stream())
            .anyMatch(comment -> !comment.bot());
    boolean humanIssueComment = issueComments.stream().anyMatch(comment -> !comment.bot());
    return humanReview || humanThreadComment || humanIssueComment;
  }
}
