package com.simonrowe.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Carries the guardrail classification from {@link GuardrailAdvisor}, which computes it deep
 * inside the advisor chain, out to ChatTurnTracer, which reports it to Langfuse as a score at
 * the end of the turn.
 *
 * <p>Entries are removed on read, so a turn that is never scored leaves at most one stale entry
 * per session, and {@link ChatSessionCleanupService} clears those on eviction.
 */
@Component
public class GuardrailVerdictRegistry {

  private final Map<String, String> verdicts = new ConcurrentHashMap<>();

  /**
   * Records the classification for a session. Null arguments are ignored rather than throwing,
   * because guardrail bookkeeping must never break a chat turn.
   *
   * @param sessionId the chat session id
   * @param verdict SAFE, OFF_TOPIC or HARMFUL
   */
  public void record(final String sessionId, final String verdict) {
    if (sessionId == null || verdict == null) {
      return;
    }
    verdicts.put(sessionId, verdict);
  }

  /**
   * Reads and removes the verdict for a session.
   *
   * @param sessionId the chat session id
   * @return the verdict, or null if none was recorded
   */
  public String takeVerdict(final String sessionId) {
    if (sessionId == null) {
      return null;
    }
    return verdicts.remove(sessionId);
  }

  /**
   * Removes any verdict held for a session. Null is ignored rather than throwing, because
   * guardrail bookkeeping must never break a chat turn.
   *
   * @param sessionId the chat session id
   */
  public void clearSession(final String sessionId) {
    if (sessionId == null) {
      return;
    }
    verdicts.remove(sessionId);
  }
}
