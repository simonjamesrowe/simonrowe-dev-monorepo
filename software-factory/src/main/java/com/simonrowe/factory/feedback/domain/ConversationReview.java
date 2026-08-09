package com.simonrowe.factory.feedback.domain;

/** PR review submission (APPROVED, CHANGES_REQUESTED, or COMMENTED). */
public record ConversationReview(
    String author, boolean bot, String state, String body, String url) {
}
