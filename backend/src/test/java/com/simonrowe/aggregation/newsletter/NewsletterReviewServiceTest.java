package com.simonrowe.aggregation.newsletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.aggregation.newsletter.NewsletterCandidate.Status;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NewsletterReviewServiceTest {

  private static final ContentSource TLDR = new ContentSource("s", "TLDR", "https://tldr.tech",
      "dan@tldrnewsletter.com", null, ContentSource.SourceType.NEWS,
      ContentSource.ScrapeStrategy.EMAIL_NEWSLETTER, true, null, null, null);

  @Mock private NewsletterCandidateRepository candidateRepository;
  @Mock private ContentSourceRepository sourceRepository;
  @Mock private NewsletterIngestService ingestService;
  @Mock private ContentAggregationAgent aggregationAgent;

  private NewsletterReviewService service;

  @BeforeEach
  void setUp() {
    service = new NewsletterReviewService(
        candidateRepository, sourceRepository, ingestService, aggregationAgent);
    lenient().when(candidateRepository.save(any(NewsletterCandidate.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private static NewsletterCandidate candidate(final Status status) {
    return new NewsletterCandidate("c1", "TLDR", "Story", "https://example.com/story",
        "The summary.", "Big Tech", "4 minute read", "m1", "Issue", Instant.now(), 0.3,
        "Hearted", status, "Below threshold", null, Instant.now(), Instant.now());
  }

  @Test
  void promotingSavesTheStoryWithTheNewslettersSummary() {
    ScrapedContent content = new ScrapedContent("Story", "https://example.com/story", "Body",
        Instant.now(), null, null, false);
    when(candidateRepository.findById("c1")).thenReturn(Optional.of(candidate(Status.PENDING)));
    when(sourceRepository.findByName("TLDR")).thenReturn(Optional.of(TLDR));
    when(ingestService.toContent(any())).thenReturn(content);
    when(aggregationAgent.saveCuratedArticle(TLDR, content, "The summary."))
        .thenReturn(Optional.of("a1"));

    NewsletterCandidate result = service.promote("c1").orElseThrow();

    assertThat(result.status()).isEqualTo(Status.PROMOTED);
    assertThat(result.articleId()).isEqualTo("a1");
  }

  @Test
  void dismissedStoryCanStillBePromoted() {
    when(candidateRepository.findById("c1"))
        .thenReturn(Optional.of(candidate(Status.DISMISSED)));
    when(sourceRepository.findByName("TLDR")).thenReturn(Optional.of(TLDR));
    when(aggregationAgent.saveCuratedArticle(eq(TLDR), any(), any()))
        .thenReturn(Optional.empty());

    NewsletterCandidate result = service.promote("c1").orElseThrow();

    assertThat(result.status()).isEqualTo(Status.PROMOTED);
    assertThat(result.reason()).contains("Already on the site");
  }

  @Test
  void storyAlreadyOnTheSiteCannotBePromotedAgain() {
    when(candidateRepository.findById("c1")).thenReturn(Optional.of(candidate(Status.ACCEPTED)));

    assertThatThrownBy(() -> service.promote("c1"))
        .isInstanceOf(NewsletterReviewService.InvalidDecisionException.class);
    verify(aggregationAgent, never()).saveCuratedArticle(any(), any(), any());
  }

  @Test
  void dismissingOnlyAppliesToTheQueue() {
    when(candidateRepository.findById("c1")).thenReturn(Optional.of(candidate(Status.PENDING)));
    assertThat(service.dismiss("c1")).get()
        .extracting(NewsletterCandidate::status).isEqualTo(Status.DISMISSED);

    when(candidateRepository.findById("c2")).thenReturn(Optional.of(candidate(Status.PROMOTED)));
    assertThatThrownBy(() -> service.dismiss("c2"))
        .isInstanceOf(NewsletterReviewService.InvalidDecisionException.class);
  }

  @Test
  void anUnknownIdIsEmpty() {
    when(candidateRepository.findById("nope")).thenReturn(Optional.empty());

    assertThat(service.promote("nope")).isEmpty();
    assertThat(service.dismiss("nope")).isEmpty();
  }
}
