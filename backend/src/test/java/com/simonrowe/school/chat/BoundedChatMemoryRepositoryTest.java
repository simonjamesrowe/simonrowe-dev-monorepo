package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The bound on Term Time's conversation store.
 *
 * <p>Term Time is unauthenticated and the conversation id arrives in the STOMP frame, so a client
 * that varies it allocates one map entry per request. Spring AI's own in-memory repository never
 * removes anything, and a scheduled sweeper does not close the gap either — a burst inside one
 * sweep interval still allocates without limit. The bound has to be enforced by the store, on
 * write, which is what these pin.
 */
class BoundedChatMemoryRepositoryTest {

  /** A clock the test moves by hand, so TTL behaviour needs no sleeping. */
  private static final class TickingClock extends Clock {

    private Instant now = Instant.parse("2026-09-10T10:00:00Z");

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    private void advance(final Duration amount) {
      now = now.plus(amount);
    }
  }

  private static List<Message> messages(final String text) {
    return List.of(new UserMessage(text));
  }

  private static String textOf(final List<Message> stored) {
    return stored.isEmpty() ? "" : stored.get(0).getText();
  }

  @Test
  @DisplayName("a conversation is stored and read back")
  void storesAndReads() {
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(10, Duration.ofMinutes(30), new TickingClock());
    store.saveAll("a", messages("what day is PE?"));

    assertThat(textOf(store.findByConversationId("a"))).isEqualTo("what day is PE?");
  }

  @Test
  @DisplayName("the conversation count never exceeds the cap")
  void capIsEnforced() {
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(3, Duration.ofMinutes(30), new TickingClock());
    for (int i = 0; i < 100; i++) {
      store.saveAll("session-" + i, messages("q" + i));
    }

    assertThat(store.size()).isEqualTo(3);
    assertThat(store.findByConversationId("session-99")).isNotEmpty();
    assertThat(store.findByConversationId("session-0")).isEmpty();
  }

  @Test
  @DisplayName("eviction is least-recently-USED, so an active conversation outlives a dormant one")
  void evictionIsLeastRecentlyUsed() {
    // The failure this prevents: a visitor mid-conversation loses their history because someone
    // else started chatting after them. Reading counts as a use, which is what the chat memory
    // advisor does at the start of every turn.
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(2, Duration.ofMinutes(30), new TickingClock());
    store.saveAll("old-but-active", messages("first"));
    store.saveAll("dormant", messages("second"));

    store.findByConversationId("old-but-active");
    store.saveAll("newcomer", messages("third"));

    assertThat(store.findByConversationId("old-but-active")).isNotEmpty();
    assertThat(store.findByConversationId("dormant")).isEmpty();
  }

  @Test
  @DisplayName("a conversation expires once its TTL passes")
  void expiresAfterTheTimeToLive() {
    final TickingClock clock = new TickingClock();
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(10, Duration.ofMinutes(30), clock);
    store.saveAll("a", messages("what day is PE?"));

    clock.advance(Duration.ofMinutes(29));
    assertThat(store.findByConversationId("a")).isNotEmpty();

    clock.advance(Duration.ofMinutes(2));
    assertThat(store.findByConversationId("a")).isEmpty();
  }

  @Test
  @DisplayName("an expired conversation is removed by the read, not merely hidden from it")
  void expiredEntriesAreRemovedOnRead() {
    // Otherwise a caller that only ever reads could pin dead entries against the cap forever,
    // starving live conversations of room.
    final TickingClock clock = new TickingClock();
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(10, Duration.ofMinutes(30), clock);
    store.saveAll("a", messages("q"));
    clock.advance(Duration.ofHours(1));

    store.findByConversationId("a");

    assertThat(store.size()).isZero();
  }

  @Test
  @DisplayName("writing sweeps every expired conversation, not just the one being written")
  void writeSweepsTheWholeStore() {
    final TickingClock clock = new TickingClock();
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(10, Duration.ofMinutes(30), clock);
    store.saveAll("a", messages("q"));
    store.saveAll("b", messages("q"));
    clock.advance(Duration.ofHours(1));

    store.saveAll("c", messages("q"));

    assertThat(store.size()).isEqualTo(1);
    assertThat(store.findConversationIds()).containsExactly("c");
  }

  @Test
  @DisplayName("a later write refreshes the TTL")
  void writingRefreshesTheTtl() {
    final TickingClock clock = new TickingClock();
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(10, Duration.ofMinutes(30), clock);
    store.saveAll("a", messages("first"));

    clock.advance(Duration.ofMinutes(25));
    store.saveAll("a", messages("second"));
    clock.advance(Duration.ofMinutes(25));

    assertThat(textOf(store.findByConversationId("a"))).isEqualTo("second");
  }

  @Test
  @DisplayName("the stored list is a copy, so a caller mutating its own list cannot reach in")
  void storedMessagesAreCopied() {
    // MessageWindowChatMemory reuses and truncates the list it passes; holding the reference
    // would let a later truncation silently rewrite history we already stored.
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(10, Duration.ofMinutes(30), new TickingClock());
    final List<Message> caller = new ArrayList<>(messages("kept"));
    store.saveAll("a", caller);

    caller.clear();

    assertThat(store.findByConversationId("a")).hasSize(1);
  }

  @Test
  @DisplayName("deleting and saving nothing both clear a conversation")
  void clearing() {
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(10, Duration.ofMinutes(30), new TickingClock());
    store.saveAll("a", messages("q"));
    store.saveAll("b", messages("q"));

    store.deleteByConversationId("a");
    store.saveAll("b", List.of());

    assertThat(store.size()).isZero();
  }

  @Test
  @DisplayName("a null conversation id is ignored rather than stored under a shared key")
  void nullConversationIdIgnored() {
    // A null key in a shared store is one conversation every anonymous caller can read.
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(10, Duration.ofMinutes(30), new TickingClock());
    store.saveAll(null, messages("q"));

    assertThat(store.size()).isZero();
    assertThat(store.findByConversationId(null)).isEmpty();
  }
}
