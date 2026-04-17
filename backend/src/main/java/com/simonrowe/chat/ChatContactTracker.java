package com.simonrowe.chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ChatContactTracker {

  private final Set<String> submittedSessions = ConcurrentHashMap.newKeySet();

  public boolean hasSubmitted(final String sessionId) {
    return submittedSessions.contains(sessionId);
  }

  public void markSubmitted(final String sessionId) {
    submittedSessions.add(sessionId);
  }

  public void clearSession(final String sessionId) {
    submittedSessions.remove(sessionId);
  }
}
