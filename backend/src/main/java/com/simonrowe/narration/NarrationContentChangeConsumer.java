package com.simonrowe.narration;

import com.simonrowe.events.ContentChangeEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NarrationContentChangeConsumer {

  private final NarrationService narrationService;

  public NarrationContentChangeConsumer(
      final NarrationService narrationService
  ) {
    this.narrationService = narrationService;
  }

  @KafkaListener(topics = "content-changes", groupId = "narration-lifecycle")
  public void handle(final ContentChangeEvent event) {
    // Blogs only. Aggregated articles are immutable snapshots, and an article summary's
    // narration is already content-addressed over the summary text, so regenerating a
    // summary produces a new narration id and marks the old audio stale for free.
    if (event.contentType() == ContentChangeEvent.ContentType.BLOG) {
      narrationService.invalidate(NarrationContentType.BLOG, event.contentId());
    }
  }
}
