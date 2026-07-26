package com.simonrowe.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Carries the number of tool calls executed during a chat turn from
 * {@link CountingToolCallingManager}, which observes the actual tool-execution rounds, out to
 * {@link ChatTurnTracer}, which reports it to Langfuse as the {@code tool-call-count} score at
 * the end of the turn.
 *
 * <p>Entries are removed on read, so a turn that is never scored leaves at most one stale entry
 * per session, and {@link ChatSessionCleanupService} clears those on eviction.
 */
@Component
public class ToolCallCounter {

  private final Map<String, Integer> counts = new ConcurrentHashMap<>();

  /**
   * Adds to the running total for a session. Null {@code sessionId} is ignored rather than
   * throwing, because telemetry bookkeeping must never break a chat turn or tool execution.
   *
   * @param sessionId the chat session id
   * @param count the number of tool calls to add
   */
  public void increment(final String sessionId, final int count) {
    if (sessionId == null) {
      return;
    }
    counts.merge(sessionId, count, Integer::sum);
  }

  /**
   * Reads and removes the tool-call count for a session.
   *
   * @param sessionId the chat session id
   * @return the accumulated count, or 0 if none was recorded
   */
  public int takeCount(final String sessionId) {
    if (sessionId == null) {
      return 0;
    }
    Integer count = counts.remove(sessionId);
    return count == null ? 0 : count;
  }

  /**
   * Removes any count held for a session. Null is ignored rather than throwing, because
   * telemetry bookkeeping must never break a chat turn.
   *
   * @param sessionId the chat session id
   */
  public void clearSession(final String sessionId) {
    if (sessionId == null) {
      return;
    }
    counts.remove(sessionId);
  }
}
