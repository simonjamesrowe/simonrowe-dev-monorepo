package com.simonrowe.narration;

import com.simonrowe.events.ContentChangeEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NarrationContentChangeConsumer {

  private final BlogNarrationService narrationService;

  public NarrationContentChangeConsumer(
      final BlogNarrationService narrationService
  ) {
    this.narrationService = narrationService;
  }

  @KafkaListener(topics = "content-changes", groupId = "narration-lifecycle")
  public void handle(final ContentChangeEvent event) {
    if (event.contentType() == ContentChangeEvent.ContentType.BLOG) {
      narrationService.invalidateBlog(event.contentId());
    }
  }
}
