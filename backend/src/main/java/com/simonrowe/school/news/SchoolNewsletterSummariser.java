package com.simonrowe.school.news;

import com.embabel.agent.api.common.Ai;
import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.YearGroups;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the short summary of a newsletter shown on the Term Time page.
 *
 * <p>Called once per newsletter, not per visit: {@link SchoolNewsService} stores the result and
 * serves it to everyone, so the page never waits on a model and a visitor cannot cause a call.
 */
@Component
public class SchoolNewsletterSummariser {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolNewsletterSummariser.class);

  /** A newsletter runs to about 9,000 characters; this leaves room for a long one. */
  static final int MAX_INPUT_CHARS = 16_000;
  static final int MAX_POINTS = 6;
  static final int MAX_HEADLINE_CHARS = 80;
  static final int MAX_POINT_CHARS = 280;
  private static final ZoneId SCHOOL_ZONE = ZoneId.of("Europe/London");

  private static final String PROMPT = """
      Summarise this primary school newsletter for a busy parent who will spend thirty seconds \
      on it.

      Return:
      - headline: what this week's newsletter is mostly about, in at most eight words. Plain, \
      not a pun. For example "Harvest festival and parents' evening".
      - points: between three and six things a parent needs to know, most useful first. Lead \
      with anything a parent must do or a date they must remember, then news. Each point is one \
      or two short sentences, starting with the key fact in a few words.
      - For each point, yearGroups lists the year groups it concerns, written "Reception" or \
      "Year 1" to "Year 6". Leave it empty when it concerns the whole school.

      Rules:
      - Never include a child's name. Leave out "stars of the week", class awards, birthdays \
      and any other list of pupils entirely. Staff names are fine only where a parent needs \
      them, such as who to contact.
      - Only say what the newsletter says. Never add a date, time, place or amount it does not \
      give.
      - Dates are British: write "Monday 2 November", never "11/2".
      - British English.

      Newsletter title: %s
      Published: %s

      Text:
      %s
      """;

  private final Ai ai;
  private final SchoolProperties properties;
  private final SchoolUsageRecorder usageRecorder;
  private final Clock clock;

  public SchoolNewsletterSummariser(final Ai ai, final SchoolProperties properties,
      final SchoolUsageRecorder usageRecorder, final Clock clock) {
    this.ai = ai;
    this.properties = properties;
    this.usageRecorder = usageRecorder;
    this.clock = clock;
  }

  /**
   * Summarises one newsletter.
   *
   * @param newsletter the newsletter's document
   * @return the checked summary, or empty when the model gave nothing usable or failed
   */
  public Optional<NewsletterSummary> summarise(final SchoolDocument newsletter) {
    final String body = newsletter.body();
    if (body == null || body.isBlank()) {
      return Optional.empty();
    }
    final String text = body.length() > MAX_INPUT_CHARS ? body.substring(0, MAX_INPUT_CHARS) : body;
    final String model = properties.chatModel();
    final SummarisedNewsletter raw;
    try {
      raw = ai.withLlm(model).createObjectIfPossible(
          PROMPT.formatted(newsletter.title(), publishedDate(newsletter), text),
          SummarisedNewsletter.class);
    } catch (RuntimeException e) {
      LOG.warn("Could not summarise newsletter '{}': {}", newsletter.title(), e.getMessage());
      return Optional.empty();
    }
    usageRecorder.recordEstimated(SchoolUsage.Kind.SUMMARISE, model,
        PROMPT.length() + (long) text.length(), raw == null ? 0 : 800);
    return checked(raw).map(summary -> new NewsletterSummary(newsletter.id(),
        newsletter.contentHash(), summary.headline(),
        summary.points().stream()
            .map(point -> new NewsletterSummary.Point(point.text(), point.yearGroups()))
            .toList(),
        model, Instant.now(clock)));
  }

  /**
   * Trims, caps and sanitises what the model returned.
   *
   * <p>Package-private so the rules can be asserted without a model. A summary with no usable
   * point is no summary: the page then shows the newsletter's link and says the summary is on
   * its way, which is better than a headline over an empty list.
   *
   * @param raw the model's output, possibly null
   * @return the summary to store, or empty
   */
  static Optional<SummarisedNewsletter> checked(final SummarisedNewsletter raw) {
    if (raw == null) {
      return Optional.empty();
    }
    final List<SummarisedNewsletter.Point> points = raw.points().stream()
        .filter(point -> point != null && point.text() != null && !point.text().isBlank())
        .limit(MAX_POINTS)
        .map(point -> new SummarisedNewsletter.Point(
            cap(point.text(), MAX_POINT_CHARS), YearGroups.sanitise(point.yearGroups())))
        .toList();
    if (points.isEmpty()) {
      return Optional.empty();
    }
    final String headline = raw.headline() == null || raw.headline().isBlank()
        ? "This week's newsletter"
        : cap(raw.headline(), MAX_HEADLINE_CHARS);
    return Optional.of(new SummarisedNewsletter(headline, points));
  }

  private static String publishedDate(final SchoolDocument newsletter) {
    return newsletter.publishedAt() == null
        ? "not stated"
        : newsletter.publishedAt().atZone(SCHOOL_ZONE).toLocalDate().toString();
  }

  private static String cap(final String value, final int max) {
    final String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1).trim() + "…";
  }
}
