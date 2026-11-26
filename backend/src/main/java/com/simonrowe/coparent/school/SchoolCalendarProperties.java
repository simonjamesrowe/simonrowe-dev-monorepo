package com.simonrowe.coparent.school;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Suggesting Term Time's school events for a family's calendar.
 *
 * @param enabled whether suggestions are made at all
 * @param aiEnabled whether a model merges duplicate school rows and tidies titles and times;
 *     without it each group of duplicates the rules find becomes one suggestion as written
 * @param model the model that does it
 * @param horizonDays how far ahead to suggest events
 * @param mailboxFamilyId the family whose own mailbox Term Time reads. That family is also
 *     offered events from school email that has not been published to everyone (a letter only
 *     they were sent); every other family sees published events only. Blank for none
 */
@ConfigurationProperties("coparent.school-calendar")
public record SchoolCalendarProperties(
    boolean enabled,
    boolean aiEnabled,
    String model,
    Integer horizonDays,
    String mailboxFamilyId
) {

  /** Long enough to cover a half term ahead, short enough that the first sync is readable. */
  static final int DEFAULT_HORIZON_DAYS = 60;

  public SchoolCalendarProperties {
    horizonDays = horizonDays == null || horizonDays < 1 ? DEFAULT_HORIZON_DAYS : horizonDays;
    mailboxFamilyId = mailboxFamilyId == null ? "" : mailboxFamilyId.strip();
  }
}
