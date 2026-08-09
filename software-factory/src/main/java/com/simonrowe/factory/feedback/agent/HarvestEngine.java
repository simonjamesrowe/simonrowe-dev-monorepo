package com.simonrowe.factory.feedback.agent;

import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.ReviewConversation;
import java.util.List;
import java.util.function.Consumer;

/** Extracts durable lessons from a review conversation. */
public interface HarvestEngine {
  List<Lesson> harvest(
      FeedbackRequest request, ReviewConversation conversation, Consumer<String> heartbeat);
}
