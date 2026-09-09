package com.simonrowe.school;

import java.time.Clock;
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
}
