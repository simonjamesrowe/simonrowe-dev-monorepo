package com.simonrowe.school.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

/**
 * An in-memory conversation store that cannot grow without limit.
 *
 * <p>Spring AI's {@code InMemoryChatMemoryRepository} is a bare {@code ConcurrentHashMap} keyed on
 * conversation id, and nothing ever removes an entry. That is survivable behind the portfolio
 * chat, which has a scheduled sweeper evicting sessions idle for thirty minutes. It is not
 * survivable here: <b>Term Time is unauthenticated</b>, the conversation id is whatever the
 * browser puts in the STOMP frame's {@code sessionId}, and a client that varies it costs one map
 * entry per request with no ceiling and no rate limit in front of it. A TTL sweeper alone does
 * not close that — a burst inside one sweep interval still allocates without bound.
 *
 * <p>So the bound is enforced on write, by the store itself, and there is no scheduler:
 *
 * <ul>
 *   <li><b>A hard cap on conversations</b>, evicting least-recently-used. Reaching the cap
 *       degrades the oldest conversation to forgetting its history, which is exactly what
 *       happens today for every conversation and is a far better failure than an OOM.</li>
 *   <li><b>A TTL</b>, so an ordinary quiet stack does not hold yesterday's chats. Expiry is
 *       checked on read for the key being read, and swept across the map on write — reads stay
 *       O(1) and the sweep is bounded by the cap.</li>
 * </ul>
 *
 * <p>Access order is what makes the LRU real: {@link #findByConversationId} counts as a use, so
 * an active conversation is never evicted ahead of a dormant one purely because it started
 * earlier.
 *
 * <p>Not a Spring bean and not generic infrastructure — {@code SchoolConfiguration} constructs
 * it for the one client that needs it. Publishing it would put a second {@code ChatMemory}
 * candidate in the context for {@code ChatService} to pick from by accident.
 */
public class BoundedChatMemoryRepository implements ChatMemoryRepository {

  private final int maxConversations;
  private final Duration timeToLive;
  private final Clock clock;

  /**
   * Access-ordered, so the eldest entry is the least recently <i>used</i> rather than the
   * least recently created. Guarded by {@code this}: one operation per chat turn makes lock
   * contention irrelevant, and it keeps the size cap and the TTL sweep atomic with respect to
   * each other.
   */
  private final Map<String, Conversation> conversations;

  /**
   * Creates a store.
   *
   * @param maxConversations the most conversations to retain; beyond this the least recently
   *     used is dropped
   * @param timeToLive how long a conversation survives without being touched
   * @param clock the clock expiry is measured against
   */
  public BoundedChatMemoryRepository(
      final int maxConversations, final Duration timeToLive, final Clock clock) {
    this.maxConversations = Math.max(1, maxConversations);
    this.timeToLive = timeToLive;
    this.clock = clock;
    this.conversations = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(final Map.Entry<String, Conversation> eldest) {
        return size() > BoundedChatMemoryRepository.this.maxConversations;
      }
    };
  }

  @Override
  public synchronized List<String> findConversationIds() {
    sweepExpired();
    return List.copyOf(conversations.keySet());
  }

  @Override
  public synchronized List<Message> findByConversationId(final String conversationId) {
    if (conversationId == null) {
      return List.of();
    }
    final Conversation conversation = conversations.get(conversationId);
    if (conversation == null) {
      return List.of();
    }
    if (hasExpired(conversation)) {
      // Removed rather than merely ignored, so a caller that reads and never writes cannot
      // pin a dead entry against the cap indefinitely.
      conversations.remove(conversationId);
      return List.of();
    }
    return List.copyOf(conversation.messages());
  }

  @Override
  public synchronized void saveAll(final String conversationId, final List<Message> messages) {
    if (conversationId == null) {
      return;
    }
    sweepExpired();
    if (messages == null || messages.isEmpty()) {
      conversations.remove(conversationId);
      return;
    }
    // Copied on the way in. The caller owns the list it passed and Spring AI's window memory
    // reuses one, so storing the reference would let a later truncation mutate what we hold.
    conversations.put(
        conversationId, new Conversation(new ArrayList<>(messages), clock.instant()));
  }

  @Override
  public synchronized void deleteByConversationId(final String conversationId) {
    if (conversationId != null) {
      conversations.remove(conversationId);
    }
  }

  /**
   * How many conversations are currently held. Test seam and a natural gauge if this ever wants
   * a metric.
   *
   * @return the conversation count, expired entries included until the next sweep
   */
  synchronized int size() {
    return conversations.size();
  }

  private void sweepExpired() {
    final Iterator<Map.Entry<String, Conversation>> entries =
        conversations.entrySet().iterator();
    while (entries.hasNext()) {
      if (hasExpired(entries.next().getValue())) {
        entries.remove();
      }
    }
  }

  private boolean hasExpired(final Conversation conversation) {
    return conversation.touchedAt().plus(timeToLive).isBefore(clock.instant());
  }

  /**
   * One stored conversation.
   *
   * @param messages the retained messages
   * @param touchedAt when it was last written
   */
  private record Conversation(List<Message> messages, Instant touchedAt) {
  }
}
