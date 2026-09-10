package com.simonrowe.school;

import com.simonrowe.chat.ToolFilteringChatMemory;
import com.simonrowe.school.chat.BoundedChatMemoryRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SchoolProperties} and the clock the school module reads dates from.
 *
 * <p>Deliberately not gated on {@code school.enabled}: the properties object must bind even when
 * the feature is off, so the disabled-path branches can read the flag they are gating on.
 */
@Configuration
@EnableConfigurationProperties({SchoolProperties.class, SchoolGmailProperties.class})
public class SchoolConfiguration {

  /** Ten exchanges, matching the portfolio chat's window. */
  private static final int SCHOOL_MEMORY_MAX_MESSAGES = 20;

  /**
   * The clock every date question is resolved against.
   *
   * <p>Injected rather than calling {@code LocalDate.now()} inline so that "what is on this week"
   * and "which academic year are we in" are testable without waiting for a Tuesday. The academic
   * year boundary is 1 September, so the interesting cases are all on specific dates.
   *
   * <p>{@code @ConditionalOnMissingBean} because a {@code Clock} is a natural thing for another
   * module to want later, and two of them in one context is a startup failure rather than
   * something anyone would notice in review.
   *
   * @return the system clock in the default zone
   */
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock clock() {
    return Clock.systemDefaultZone();
  }

  /**
   * Term Time's conversation memory.
   *
   * <p>Term Time shipped with none at all — not a small window, none — because
   * {@code SchoolChatService} takes the {@code ChatClient.Builder} rather than the portfolio
   * chat's fully-configured {@code ChatClient} bean, and so inherited none of its advisors. The
   * effect on a real conversation: "what day is PE?" correctly asks which year group, "year 6"
   * arrives with the word PE nowhere in the model's context, and the visitor has to restate the
   * whole question to get an answer.
   *
   * <p>Two decisions worth keeping:
   *
   * <ul>
   *   <li><b>{@link ToolFilteringChatMemory} is load-bearing here, not tidiness.</b> Term Time
   *       is entirely tool-driven, so an unfiltered store would keep assistant messages carrying
   *       {@code tool_calls} alongside their {@code ToolResponseMessage}s — and a message window
   *       truncates on count, so it will eventually cut between the two. OpenAI rejects a
   *       conversation containing a tool call with no matching response, so the failure would be
   *       a 400 that appears only after enough turns to push the pair apart. It also keeps the
   *       window spent on what was actually said.</li>
   *   <li><b>{@link BoundedChatMemoryRepository} rather than Spring AI's unbounded default</b>,
   *       because this endpoint is unauthenticated and the conversation id is client-supplied.
   *       See that class for why a sweeper alone is not enough.</li>
   * </ul>
   *
   * <p>Twenty messages matches the portfolio chat. It is ten exchanges, and it sits under
   * {@code SchoolStreamController}'s twenty-message-per-session cap, so the window is never the
   * thing a visitor hits first.
   *
   * @param maxSessions the most conversations to hold at once
   * @param ttlMinutes how long a conversation survives untouched
   * @param clock the clock expiry is measured against
   * @return the school chat memory
   */
  @Bean
  public ChatMemory schoolChatMemory(
      @Value("${school.chat.memory.max-sessions:500}") final int maxSessions,
      @Value("${school.chat.memory.ttl-minutes:30}") final long ttlMinutes,
      final Clock clock) {
    return new ToolFilteringChatMemory(
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new BoundedChatMemoryRepository(
                maxSessions, Duration.ofMinutes(ttlMinutes), clock))
            .maxMessages(SCHOOL_MEMORY_MAX_MESSAGES)
            .build());
  }
}
