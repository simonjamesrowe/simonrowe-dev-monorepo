package com.simonrowe.factory.feedback.domain;

/** Single comment in a PR review thread or issue conversation. */
public record ConversationComment(
    String author, boolean bot, String body, String path, Integer line, String diffHunk,
    String url) {
}
