package com.simonrowe.school.news;

import com.simonrowe.school.ingest.SchoolLinkFilter;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The school's newsletters, as the Term Time page shows them, and the job that summarises them.
 *
 * <p><b>Public content only, whoever asks.</b> The read is fixed to {@link Visibility#PUBLIC}:
 * the page sends no token, and a card shown to every visitor has no reason to reach anything
 * else.
 */
@Service
public class SchoolNewsService {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolNewsService.class);

  /** How far back the newsletter list reaches: a little over a term. */
  static final Duration NEWSLETTER_WINDOW = Duration.ofDays(120);

  /** How many of the newest newsletters are kept summarised. */
  static final int SUMMARISED_NEWSLETTERS = 6;

  /**
   * How many summaries one run may write. The first run after deploy has a term's worth to
   * catch up on, and spreading it over a few hourly runs keeps one run short.
   */
  static final int MAX_SUMMARIES_PER_RUN = 3;

  private static final List<Visibility> PUBLIC_ONLY = List.of(Visibility.PUBLIC);
  private static final ZoneId SCHOOL_ZONE = ZoneId.of("Europe/London");

  private final SchoolDocumentRepository documents;
  private final NewsletterSummaryRepository summaries;
  private final SchoolNewsletterSummariser summariser;
  private final SchoolLinkFilter linkFilter;
  private final Clock clock;

  public SchoolNewsService(
      final SchoolDocumentRepository documents,
      final NewsletterSummaryRepository summaries,
      final SchoolNewsletterSummariser summariser,
      final SchoolLinkFilter linkFilter,
      final Clock clock) {
    this.documents = documents;
    this.summaries = summaries;
    this.summariser = summariser;
    this.linkFilter = linkFilter;
    this.clock = clock;
  }

  /**
   * The newest newsletters, each with its summary when one has been written.
   *
   * @param limit how many to return
   * @return newsletters, newest first
   */
  public List<Newsletter> newsletters(final int limit) {
    final List<SchoolDocument> found = newsletterDocuments(limit);
    final Map<String, NewsletterSummary> written = summariesFor(found);
    return found.stream()
        .map(document -> new Newsletter(document.id(), document.title(), document.sourceRef(),
            document.publishedAt(), current(written.get(document.id()), document)))
        .toList();
  }

  /**
   * Writes a summary for each recent newsletter that has none, or whose text has changed.
   *
   * <p>A newsletter Claude Code is still writing is left for the next run and does not count
   * towards the cap: asking after it is one status call.
   *
   * @return how many were written
   */
  public int summariseMissing() {
    final List<SchoolDocument> recent = newsletterDocuments(SUMMARISED_NEWSLETTERS);
    final Map<String, NewsletterSummary> written = summariesFor(recent);
    int count = 0;
    for (SchoolDocument newsletter : recent) {
      if (count >= MAX_SUMMARIES_PER_RUN) {
        break;
      }
      if (current(written.get(newsletter.id()), newsletter) != null) {
        continue;
      }
      final SchoolNewsletterSummariser.Attempt attempt = summariser.summarise(newsletter);
      if (attempt.status() == SchoolNewsletterSummariser.Status.WRITTEN) {
        summaries.save(attempt.summary());
        LOG.info("Summarised newsletter '{}' with {}", newsletter.title(),
            attempt.summary().model());
        count++;
      }
    }
    return count;
  }

  /**
   * Public newsletter pages, newest first, one per publication date.
   *
   * <p>De-duplicated by date because the school sometimes publishes an issue twice under two
   * ids: on the portal, 166 and 167 are both "Newsletter - 02nd October 2026", the second a
   * fuller re-publish of the first. Their text differs, so the content hash cannot catch it, and
   * two cards for one Friday would look like a fault. The longer text is kept, since a
   * re-publish adds to an issue rather than cutting it down.
   */
  private List<SchoolDocument> newsletterDocuments(final int limit) {
    final Instant now = Instant.now(clock);
    final Map<LocalDate, SchoolDocument> byDate = new LinkedHashMap<>();
    documents.findPublishedBetween(PUBLIC_ONLY, List.of(SchoolSourceType.WEBSITE_PAGE),
            now.minus(NEWSLETTER_WINDOW), now.plus(Duration.ofDays(1))).stream()
        .filter(document -> document.publishedAt() != null)
        .filter(document -> linkFilter.isAutoFetchable(document.sourceRef()))
        .forEach(document -> byDate.merge(
            document.publishedAt().atZone(SCHOOL_ZONE).toLocalDate(), document,
            SchoolNewsService::longer));
    return byDate.values().stream().limit(limit).toList();
  }

  private static SchoolDocument longer(final SchoolDocument a, final SchoolDocument b) {
    return lengthOf(b) > lengthOf(a) ? b : a;
  }

  private static int lengthOf(final SchoolDocument document) {
    return document.body() == null ? 0 : document.body().length();
  }

  private Map<String, NewsletterSummary> summariesFor(final List<SchoolDocument> newsletters) {
    return StreamSupport.stream(
            summaries.findAllById(newsletters.stream().map(SchoolDocument::id).toList())
                .spliterator(),
            false)
        .collect(Collectors.toMap(NewsletterSummary::id, Function.identity()));
  }

  /** The summary when it was written from the text as it stands, otherwise null. */
  private static NewsletterSummary current(
      final NewsletterSummary summary, final SchoolDocument document) {
    return summary != null && Objects.equals(summary.contentHash(), document.contentHash())
        ? summary
        : null;
  }

  /**
   * One newsletter.
   *
   * @param id the document id
   * @param title the newsletter's title as the school gave it
   * @param url where the school published it
   * @param publishedAt the date it carries
   * @param summary the summary, or null while it has not been written
   */
  public record Newsletter(
      String id, String title, String url, Instant publishedAt, NewsletterSummary summary) {
  }
}
