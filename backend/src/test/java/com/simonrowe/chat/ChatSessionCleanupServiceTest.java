package com.simonrowe.chat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatSessionCleanupServiceTest {

  private static final long DEFAULT_MAX_INACTIVE_MINUTES = 30;

  @Mock
  private ChatService chatService;

  private ChatSessionCleanupService cleanupService;

  @BeforeEach
  void setUp() {
    cleanupService = new ChatSessionCleanupService(chatService, DEFAULT_MAX_INACTIVE_MINUTES);
  }

  @Test
  void cleanupStaleSessionsEvictsSessionsInactiveForMoreThanMaxMinutes() {
    final ConcurrentHashMap<String, Instant> sessionActivity = new ConcurrentHashMap<>();
    sessionActivity.put("stale-session-1",
        Instant.now().minusSeconds(DEFAULT_MAX_INACTIVE_MINUTES * 60 + 60));
    sessionActivity.put("stale-session-2",
        Instant.now().minusSeconds(DEFAULT_MAX_INACTIVE_MINUTES * 60 + 120));
    when(chatService.getSessionActivity()).thenReturn(sessionActivity);

    cleanupService.cleanupStaleSessions();

    verify(chatService).evictSession("stale-session-1");
    verify(chatService).evictSession("stale-session-2");
  }

  @Test
  void cleanupStaleSessionsPreservesRecentlyActiveSessions() {
    final ConcurrentHashMap<String, Instant> sessionActivity = new ConcurrentHashMap<>();
    sessionActivity.put("active-session", Instant.now().minusSeconds(60));
    when(chatService.getSessionActivity()).thenReturn(sessionActivity);

    cleanupService.cleanupStaleSessions();

    verify(chatService, never()).evictSession("active-session");
  }

  @Test
  void cleanupStaleSessionsEvictsOnlyStaleSessionsWhenMixed() {
    final ConcurrentHashMap<String, Instant> sessionActivity = new ConcurrentHashMap<>();
    sessionActivity.put("stale-session",
        Instant.now().minusSeconds(DEFAULT_MAX_INACTIVE_MINUTES * 60 + 60));
    sessionActivity.put("active-session", Instant.now().minusSeconds(60));
    when(chatService.getSessionActivity()).thenReturn(sessionActivity);

    cleanupService.cleanupStaleSessions();

    verify(chatService).evictSession("stale-session");
    verify(chatService, never()).evictSession("active-session");
  }

  @Test
  void cleanupStaleSessionsDoesNothingWhenNoSessionsExist() {
    when(chatService.getSessionActivity()).thenReturn(new ConcurrentHashMap<>());

    cleanupService.cleanupStaleSessions();

    verify(chatService, never()).evictSession(anyString());
  }

  @Test
  void cleanupStaleSessionsDoesNothingWhenAllSessionsAreActive() {
    final ConcurrentHashMap<String, Instant> sessionActivity = new ConcurrentHashMap<>();
    sessionActivity.put("active-1", Instant.now().minusSeconds(60));
    sessionActivity.put("active-2", Instant.now().minusSeconds(120));
    when(chatService.getSessionActivity()).thenReturn(sessionActivity);

    cleanupService.cleanupStaleSessions();

    verify(chatService, never()).evictSession(anyString());
  }

  @Test
  void cleanupStaleSessionsRespectsCustomMaxInactiveMinutes() {
    final long maxInactiveMinutes = 5;
    final ChatService localChatService = mock(ChatService.class);
    final ChatSessionCleanupService shortTimeoutService =
        new ChatSessionCleanupService(localChatService, maxInactiveMinutes);

    final ConcurrentHashMap<String, Instant> sessionActivity = new ConcurrentHashMap<>();
    sessionActivity.put("stale-under-5min",
        Instant.now().minusSeconds(maxInactiveMinutes * 60 + 30));
    when(localChatService.getSessionActivity()).thenReturn(sessionActivity);

    shortTimeoutService.cleanupStaleSessions();

    verify(localChatService).evictSession("stale-under-5min");
  }

  @Test
  void cleanupStaleSessionsPreservesSessionJustUnderThreshold() {
    final ConcurrentHashMap<String, Instant> sessionActivity = new ConcurrentHashMap<>();
    // 1 second before becoming stale
    sessionActivity.put("almost-stale",
        Instant.now().minusSeconds(DEFAULT_MAX_INACTIVE_MINUTES * 60 - 1));
    when(chatService.getSessionActivity()).thenReturn(sessionActivity);

    cleanupService.cleanupStaleSessions();

    verify(chatService, never()).evictSession("almost-stale");
  }
}
