package com.simonrowe.aggregation.newsletter;

import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.newsletter.InterestProfile.Relevance;
import com.simonrowe.aggregation.newsletter.NewsletterCandidate.Status;
import com.simonrowe.school.ingest.GmailClient;
import com.simonrowe.school.ingest.GmailClient.GmailAuthException;
import com.simonrowe.webfetch.UrlFetcher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Reads an email newsletter source out of the Gmail mailbox and decides, story by story, what
 * reaches the site.
 *
 * <p>The mailbox is the one Term Time reads, with the same read-only credential
 * ({@code SCHOOL_GMAIL_*}): it is Simon's personal Gmail, and the newsletters are subscribed to
 * there. Nothing is written back to Gmail.
 *
 * <p>A run lists the source's recent messages, keeps only those that really are from it (the
 * sender address, the edition's display name, and a DKIM signature Google verified for the
 * sender's domain), parses each issue into stories, drops any story already read or already on
 * the site, scores the rest against the hearted articles, and records every one in
 * {@code newsletter_candidates}. Those at or above the threshold, up to the per-run cap, are
 * saved as articles; the rest wait in the admin review queue and cost nothing further.
 */
@Service
@EnableConfigurationProperties(NewsletterProperties.class)
public class NewsletterIngestService {

  private static final Logger log = LoggerFactory.getLogger(NewsletterIngestService.class);

  private final GmailClient gmailClient;
  private final InterestProfile interestProfile;
  private final NewsletterCandidateRepository candidateRepository;
  private final AggregatedArticleRepository articleRepository;
  private final SitemapHtmlScraper htmlScraper;
  private final ShortLinkResolver shortLinkResolver;
  private final NewsletterProperties properties;

  public NewsletterIngestService(
      final GmailClient gmailClient,
      final InterestProfile interestProfile,
      final NewsletterCandidateRepository candidateRepository,
      final AggregatedArticleRepository articleRepository,
      final SitemapHtmlScraper htmlScraper,
      final ShortLinkResolver shortLinkResolver,
      final NewsletterProperties properties) {
    this.gmailClient = gmailClient;
    this.interestProfile = interestProfile;
    this.candidateRepository = candidateRepository;
    this.articleRepository = articleRepository;
    this.htmlScraper = htmlScraper;
    this.shortLinkResolver = shortLinkResolver;
    this.properties = properties;
  }

  /**
   * Saves a curated story as an article. Implemented by {@code ContentAggregationAgent}, passed
   * in rather than injected so this service does not depend on the agent that depends on it.
   */
  @FunctionalInterface
  public interface ArticleSink {

    /**
     * Saves the story.
     *
     * @param source the edition it came from
     * @param content the story, with whatever the linked page added
     * @param summary the newsletter's summary, used instead of an LLM-written one
     * @return the saved article id, or empty when the address is already on the site
     */
    Optional<String> save(ContentSource source, ScrapedContent content, String summary);
  }

  /** What one run did. */
  public record IngestReport(int messages, int stories, int accepted, int queued) {
  }

  private record Fresh(NewsletterEmail email, NewsletterStory story, String url) {
  }

  /**
   * Ingests one newsletter source.
   *
   * @param source an {@code EMAIL_NEWSLETTER} source
   * @param since read messages received after this; null means the configured look-back
   * @param sink saves accepted stories
   * @return what was read and decided
   * @throws IllegalStateException when the mailbox cannot be read, so the source records the
   *     error rather than looking like a quiet newsletter
   */
  public IngestReport ingest(
      final ContentSource source, final Instant since, final ArticleSink sink) {
    final String sender = senderOf(source);
    final String senderDomain = sender.substring(sender.indexOf('@') + 1);
    final Instant from = since != null
        ? since : Instant.now().minus(Duration.ofDays(properties.lookbackDays()));

    final List<NewsletterEmail> issues = readIssues(source, sender, senderDomain, from);
    final List<Fresh> fresh = freshStories(issues);
    if (fresh.isEmpty()) {
      log.info("Newsletter {}: {} issues, no new stories", source.name(), issues.size());
      return new IngestReport(issues.size(), 0, 0, 0);
    }

    final List<Relevance> scores = interestProfile.score(fresh.stream()
        .map(f -> f.story().title() + ". " + f.story().summary())
        .toList());
    final List<Integer> order = new ArrayList<>();
    for (int i = 0; i < fresh.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparingDouble((Integer i) -> scores.get(i).score()).reversed());

    int accepted = 0;
    int queued = 0;
    for (int i : order) {
      final Relevance relevance = scores.get(i);
      final boolean relevant = relevance.score() >= properties.relevanceThreshold();
      final boolean underCap = accepted < properties.maxAcceptedPerRun();
      final Optional<NewsletterCandidate> claimed =
          claim(source, fresh.get(i), relevance, relevant && underCap);
      if (claimed.isEmpty()) {
        continue;
      }
      if (claimed.get().status() == Status.ACCEPTED) {
        if (saveAccepted(source, claimed.get(), sink)) {
          accepted++;
        } else {
          queued++;
        }
      } else {
        queued++;
      }
    }
    log.info("Newsletter {}: {} issues, {} new stories, {} saved, {} queued for review",
        source.name(), issues.size(), fresh.size(), accepted, queued);
    return new IngestReport(issues.size(), fresh.size(), accepted, queued);
  }

  /**
   * Builds the content an article is saved from.
   *
   * <p>The linked page is fetched for its image, date, author and body, which is what lets the
   * card carry a picture and the search index more than two sentences. The newsletter's own
   * headline is kept, because a page's {@code og:title} often carries the site's name, and the
   * issue's arrival time stands in when the page states no date. The page is fetched only when
   * its host resolves to a public address: the address came out of an email.
   *
   * @param candidate the story
   * @return the content to save
   */
  public ScrapedContent toContent(final NewsletterCandidate candidate) {
    final ScrapedContent page = UrlFetcher.isFetchableUrl(candidate.url())
        ? htmlScraper.scrapeArticlePagePublic(candidate.url())
        : null;
    if (page == null) {
      return new ScrapedContent(candidate.title(), candidate.url(), candidate.summary(),
          candidate.receivedAt(), null, null, false);
    }
    return new ScrapedContent(
        candidate.title(),
        candidate.url(),
        page.content(),
        page.publishedDate() != null ? page.publishedDate() : candidate.receivedAt(),
        page.author(),
        page.imageUrl(),
        false);
  }

  private String senderOf(final ContentSource source) {
    final String sender = source.feedUrl() == null
        ? "" : source.feedUrl().trim().toLowerCase(Locale.ROOT);
    final int at = sender.indexOf('@');
    if (at <= 0 || at == sender.length() - 1) {
      throw new IllegalStateException(
          "Newsletter source '%s' needs its sender address in feedUrl"
              .formatted(source.name()));
    }
    return sender;
  }

  private List<NewsletterEmail> readIssues(
      final ContentSource source, final String sender, final String senderDomain,
      final Instant from) {
    if (!gmailClient.isConfigured()) {
      throw new IllegalStateException(
          "No Gmail credential is configured (SCHOOL_GMAIL_*), so newsletters cannot be read");
    }
    final List<NewsletterEmail> issues = new ArrayList<>();
    try {
      final String query = "from:%s after:%d".formatted(sender, from.getEpochSecond());
      for (String id : gmailClient.listMessageIds(query)) {
        final Optional<NewsletterEmail> email =
            gmailClient.fetchRawMessage(id).map(NewsletterEmail::from);
        if (email.isEmpty()
            || !sender.equals(email.get().fromAddress())
            || !source.name().equals(email.get().displayName())) {
          continue;
        }
        if (!email.get().signedBy(senderDomain)) {
          log.warn("Skipping message {} claiming to be {}: no passing DKIM signature for {}",
              id, source.name(), senderDomain);
          continue;
        }
        issues.add(email.get());
      }
    } catch (GmailAuthException e) {
      throw new IllegalStateException("Gmail would not authenticate: " + e.getMessage(), e);
    }
    return issues;
  }

  private List<Fresh> freshStories(final List<NewsletterEmail> issues) {
    final List<Fresh> fresh = new ArrayList<>();
    final Set<String> seen = new HashSet<>();
    for (NewsletterEmail email : issues) {
      for (NewsletterStory story : TldrIssueParser.parse(email.html())) {
        final Optional<String> url = shortLinkResolver.resolve(story.url())
            .flatMap(NewsletterLinks::canonical);
        if (url.isEmpty() || !seen.add(url.get())
            || candidateRepository.existsByUrl(url.get())
            || articleRepository.existsByOriginalUrl(url.get())) {
          continue;
        }
        fresh.add(new Fresh(email, story, url.get()));
      }
    }
    return fresh;
  }

  /**
   * Records a story, claiming its address. Insert-first: a concurrent run or another edition that
   * got there first makes the unique index refuse this one, and that refusal is the dedup.
   */
  private Optional<NewsletterCandidate> claim(
      final ContentSource source, final Fresh fresh, final Relevance relevance,
      final boolean accept) {
    final Instant now = Instant.now();
    final String reason;
    if (accept) {
      reason = "Relevance %.2f is at or above the %.2f threshold"
          .formatted(relevance.score(), properties.relevanceThreshold());
    } else if (relevance.nearestTitle() == null) {
      reason = "Nothing is hearted yet, so there is nothing to judge relevance against";
    } else if (relevance.score() >= properties.relevanceThreshold()) {
      reason = "Relevant, but over the %d-per-run cap".formatted(properties.maxAcceptedPerRun());
    } else {
      reason = "Relevance %.2f is below the %.2f threshold"
          .formatted(relevance.score(), properties.relevanceThreshold());
    }
    final NewsletterCandidate candidate = new NewsletterCandidate(
        null, source.name(), fresh.story().title(), fresh.url(), fresh.story().summary(),
        fresh.story().section(), fresh.story().label(), fresh.email().id(),
        fresh.email().subject(), fresh.email().receivedAt(), relevance.score(),
        relevance.nearestTitle(), accept ? Status.ACCEPTED : Status.PENDING, reason, null,
        now, now);
    try {
      return Optional.of(candidateRepository.insert(candidate));
    } catch (DuplicateKeyException e) {
      return Optional.empty();
    }
  }

  /**
   * Saves an accepted story. A failure puts it back in the queue, saying why, rather than
   * leaving an "accepted" row with no article that nothing would ever retry.
   */
  private boolean saveAccepted(
      final ContentSource source, final NewsletterCandidate candidate, final ArticleSink sink) {
    try {
      final Optional<String> articleId =
          sink.save(source, toContent(candidate), candidate.summary());
      candidateRepository.save(candidate.decided(Status.ACCEPTED,
          articleId.isPresent() ? candidate.reason() : "Already on the site from another source",
          articleId.orElse(null), Instant.now()));
      return true;
    } catch (RuntimeException e) {
      log.warn("Saving accepted newsletter story '{}' failed; queued for review",
          candidate.title(), e);
      candidateRepository.save(candidate.decided(Status.PENDING,
          "Accepted, but saving it failed: " + e.getMessage(), null, Instant.now()));
      return false;
    }
  }
}
