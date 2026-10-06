package com.simonrowe.aggregation.newsletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.newsletter.InterestProfile.Relevance;
import com.simonrowe.aggregation.newsletter.NewsletterCandidate.Status;
import com.simonrowe.aggregation.newsletter.NewsletterIngestService.IngestReport;
import com.simonrowe.school.ingest.GmailClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class NewsletterIngestServiceTest {

  private static final ContentSource TLDR_DEV = new ContentSource(
      "s-dev", "TLDR Dev", "https://tldr.tech/dev", "dan@tldrnewsletter.com", null,
      ContentSource.SourceType.NEWS, ContentSource.ScrapeStrategy.EMAIL_NEWSLETTER,
      true, null, null, null);
  private static final String SIGNED =
      "mx.google.com; dkim=pass header.i=@tldrnewsletter.com header.s=x";
  private static final int DEV_STORIES = 12;

  @Mock private GmailClient gmailClient;
  @Mock private InterestProfile interestProfile;
  @Mock private NewsletterCandidateRepository candidateRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private SitemapHtmlScraper htmlScraper;
  @Mock private ShortLinkResolver shortLinkResolver;

  private final List<NewsletterCandidate> saved = new ArrayList<>();
  private final List<ScrapedContent> sunk = new ArrayList<>();
  private NewsletterIngestService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new NewsletterIngestService(gmailClient, interestProfile, candidateRepository,
        articleRepository, htmlScraper, shortLinkResolver, new NewsletterProperties(0.5, 15, 3));
    lenient().when(gmailClient.isConfigured()).thenReturn(true);
    lenient().when(shortLinkResolver.resolve(anyString()))
        .thenAnswer(inv -> Optional.of(inv.getArgument(0, String.class)));
    lenient().when(candidateRepository.insert(any(NewsletterCandidate.class)))
        .thenAnswer(inv -> {
          NewsletterCandidate c = inv.getArgument(0);
          NewsletterCandidate withId = new NewsletterCandidate("c" + saved.size(),
              c.sourceName(), c.title(), c.url(), c.summary(), c.section(), c.label(),
              c.messageId(), c.issueSubject(), c.receivedAt(), c.relevance(),
              c.nearestFavourite(), c.status(), c.reason(), c.articleId(), c.createdAt(),
              c.decidedAt());
          saved.add(withId);
          return withId;
        });
    lenient().when(candidateRepository.save(any(NewsletterCandidate.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private static String fixture() throws IOException {
    try (InputStream in = NewsletterIngestServiceTest.class
        .getResourceAsStream("/newsletter/tldr-dev-2026-10-06.html")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static JsonNode message(final String id, final String from, final String auth)
      throws IOException {
    String data = Base64.getUrlEncoder().encodeToString(
        fixture().getBytes(StandardCharsets.UTF_8));
    return new ObjectMapper().readTree("""
        {"id": "%s", "internalDate": "1791285506000", "payload": {
          "mimeType": "text/html", "body": {"data": "%s"},
          "headers": [{"name": "From", "value": "%s"},
                      {"name": "Subject", "value": "TLDR Dev issue"},
                      {"name": "Authentication-Results", "value": "%s"}]}}"""
        .formatted(id, data, from, auth));
  }

  private void mailbox(final JsonNode... messages) throws Exception {
    List<String> ids = new ArrayList<>();
    for (JsonNode message : messages) {
      String id = message.path("id").asString();
      ids.add(id);
      when(gmailClient.fetchRawMessage(id)).thenReturn(Optional.of(message));
    }
    when(gmailClient.listMessageIds(startsWith("from:dan@tldrnewsletter.com after:")))
        .thenReturn(ids);
  }

  /** Scores the stories in reading order: the first {@code relevant} high, the rest low. */
  private void scores(final int relevant) {
    when(interestProfile.score(anyList())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      return IntStream.range(0, texts.size())
          .mapToObj(i -> new Relevance(i < relevant ? 0.9 - i * 0.01 : 0.2, "Hearted"))
          .toList();
    });
  }

  private NewsletterIngestService.ArticleSink sink() {
    return (source, content, summary) -> {
      sunk.add(content);
      return Optional.of("article-" + sunk.size());
    };
  }

  @Test
  void savesRelevantStoriesAndQueuesTheRest() throws Exception {
    mailbox(message("m1", "TLDR Dev <dan@tldrnewsletter.com>", SIGNED));
    scores(3);

    IngestReport report = service.ingest(TLDR_DEV, null, sink());

    assertThat(report).isEqualTo(new IngestReport(1, DEV_STORIES, 3, DEV_STORIES - 3));
    assertThat(saved).hasSize(DEV_STORIES);
    assertThat(saved).filteredOn(c -> c.status() == Status.ACCEPTED).hasSize(3);
    assertThat(saved).filteredOn(c -> c.status() == Status.PENDING)
        .allSatisfy(c -> assertThat(c.reason()).contains("below the 0.50 threshold"));
    // The newsletter's own headline, the canonical URL and the issue date reach the article.
    assertThat(sunk.getFirst().title()).isEqualTo("Build an agent loop a small model can finish");
    assertThat(sunk.getFirst().url()).isEqualTo(
        "https://www.builder.io/blog/build-an-agent-loop-a-small-model-can-finish");
    assertThat(sunk.getFirst().publishedDate()).isEqualTo(Instant.ofEpochMilli(1791285506000L));
  }

  @Test
  void theCapSendsRelevantOverflowToReviewBestFirst() throws Exception {
    service = new NewsletterIngestService(gmailClient, interestProfile, candidateRepository,
        articleRepository, htmlScraper, shortLinkResolver, new NewsletterProperties(0.5, 2, 3));
    mailbox(message("m1", "TLDR Dev <dan@tldrnewsletter.com>", SIGNED));
    scores(5);

    IngestReport report = service.ingest(TLDR_DEV, null, sink());

    assertThat(report.accepted()).isEqualTo(2);
    assertThat(saved).filteredOn(c -> c.status() == Status.ACCEPTED)
        .extracting(NewsletterCandidate::relevance).containsExactly(0.9, 0.89);
    assertThat(saved).filteredOn(c -> c.reason().contains("2-per-run cap")).hasSize(3);
  }

  @Test
  void storiesAlreadyReadOrAlreadyOnTheSiteAreNotScoredAgain() throws Exception {
    mailbox(message("m1", "TLDR Dev <dan@tldrnewsletter.com>", SIGNED));
    when(candidateRepository.existsByUrl(anyString())).thenReturn(true);

    IngestReport report = service.ingest(TLDR_DEV, null, sink());

    assertThat(report.stories()).isZero();
    verify(interestProfile, never()).score(anyList());
  }

  @Test
  void anotherEditionFromTheSameAddressIsLeftForItsOwnSource() throws Exception {
    mailbox(message("m1", "TLDR Product <dan@tldrnewsletter.com>", SIGNED));

    IngestReport report = service.ingest(TLDR_DEV, null, sink());

    assertThat(report.messages()).isZero();
    verify(interestProfile, never()).score(anyList());
  }

  @Test
  void messageWithoutTheSendersDkimSignatureIsIgnored() throws Exception {
    mailbox(message("m1", "TLDR Dev <dan@tldrnewsletter.com>",
        "mx.google.com; dkim=fail header.i=@tldrnewsletter.com"));

    assertThat(service.ingest(TLDR_DEV, null, sink()).messages()).isZero();
    assertThat(saved).isEmpty();
  }

  @Test
  void saveFailurePutsTheStoryBackInTheQueue() throws Exception {
    mailbox(message("m1", "TLDR Dev <dan@tldrnewsletter.com>", SIGNED));
    scores(1);

    IngestReport report = service.ingest(TLDR_DEV, null, (source, content, summary) -> {
      throw new IllegalStateException("Kafka is down");
    });

    assertThat(report.accepted()).isZero();
    verify(candidateRepository).save(org.mockito.ArgumentMatchers.argThat(c ->
        c.status() == Status.PENDING && c.reason().contains("Kafka is down")));
  }

  @Test
  void storyClaimedConcurrentlyIsSkipped() throws Exception {
    mailbox(message("m1", "TLDR Dev <dan@tldrnewsletter.com>", SIGNED));
    scores(DEV_STORIES);
    when(candidateRepository.insert(any(NewsletterCandidate.class)))
        .thenThrow(new DuplicateKeyException("idx_newsletter_candidate_url"));

    IngestReport report = service.ingest(TLDR_DEV, null, sink());

    assertThat(report.accepted()).isZero();
    assertThat(sunk).isEmpty();
  }

  @Test
  void anUnreadableMailboxFailsTheSourceRatherThanLookingQuiet() {
    when(gmailClient.isConfigured()).thenReturn(false);

    assertThatThrownBy(() -> service.ingest(TLDR_DEV, null, sink()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No Gmail credential");
  }

  @Test
  void sourceWithoutSenderAddressFails() {
    ContentSource broken = new ContentSource("s", "TLDR", null, null, null,
        ContentSource.SourceType.NEWS, ContentSource.ScrapeStrategy.EMAIL_NEWSLETTER,
        true, null, null, null);

    assertThatThrownBy(() -> service.ingest(broken, null, sink()))
        .hasMessageContaining("sender address in feedUrl");
  }

  @Test
  void theLinkedPageSuppliesImageAndDateButNotTheHeadline() {
    NewsletterCandidate candidate = new NewsletterCandidate("c1", "TLDR Dev", "Worth building",
        "https://armstr.ng/writing/worth-building", "Small tools are worth it.", "Misc",
        "3 minute read", "m1", "s", Instant.parse("2026-10-06T11:18:26Z"), 0.7, "x",
        Status.PENDING, "r", null, Instant.now(), Instant.now());
    when(htmlScraper.scrapeArticlePagePublic(candidate.url())).thenReturn(new ScrapedContent(
        "Worth building | Armstrong", "https://armstr.ng/writing/worth-building", "Full body",
        Instant.parse("2026-10-04T00:00:00Z"), "A. Armstrong", "https://armstr.ng/og.png",
        false));

    ScrapedContent content = service.toContent(candidate);

    assertThat(content.title()).isEqualTo("Worth building");
    assertThat(content.content()).isEqualTo("Full body");
    assertThat(content.imageUrl()).isEqualTo("https://armstr.ng/og.png");
    assertThat(content.publishedDate()).isEqualTo(Instant.parse("2026-10-04T00:00:00Z"));
  }

  @Test
  void anAddressThatDoesNotResolvePubliclyIsNeverFetched() {
    NewsletterCandidate candidate = new NewsletterCandidate("c1", "TLDR Dev", "Internal",
        "http://127.0.0.1:8080/actuator", "Summary.", null, "1 minute read", "m1", "s",
        Instant.parse("2026-10-06T11:18:26Z"), 0.7, "x", Status.PENDING, "r", null,
        Instant.now(), Instant.now());

    ScrapedContent content = service.toContent(candidate);

    verify(htmlScraper, never()).scrapeArticlePagePublic(anyString());
    assertThat(content.content()).isEqualTo("Summary.");
    assertThat(content.publishedDate()).isEqualTo(candidate.receivedAt());
  }
}
