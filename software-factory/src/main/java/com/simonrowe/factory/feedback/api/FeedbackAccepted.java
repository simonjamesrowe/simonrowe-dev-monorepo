package com.simonrowe.factory.feedback.api;

/** Result of accepting a feedback workflow start request. */
public record FeedbackAccepted(String workflowId, boolean started) {
}
