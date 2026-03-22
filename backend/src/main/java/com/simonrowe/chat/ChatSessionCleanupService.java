package com.simonrowe.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class ChatSessionCleanupService {

  private static final Logger LOG =
      LoggerFactory.getLogger(ChatSessionCleanupService.class);

  private final ChatService chatService;
  private final long maxInactiveMinutes;

  public ChatSessionCleanupService(
      final ChatService chatService,
      @Value("${chat.session.max-inactive-minutes:30}") final long maxInactiveMinutes) {
    this.chatService = chatService;
    this.maxInactiveMinutes = maxInactiveMinutes;
  }

  @Scheduled(
      fixedRateString = "${chat.session.cleanup-interval-minutes:5}",
      timeUnit = TimeUnit.MINUTES
  )
  public void cleanupStaleSessions() {
    Instant cutoff = Instant.now().minus(Duration.ofMinutes(maxInactiveMinutes));
    List<String> staleIds = chatService.getSessionActivity().entrySet().stream()
        .filter(entry -> entry.getValue().isBefore(cutoff))
        .map(Map.Entry::getKey)
        .toList();

    if (!staleIds.isEmpty()) {
      LOG.info("Evicting {} stale chat sessions", staleIds.size());
      staleIds.forEach(chatService::evictSession);
    }
  }
}
