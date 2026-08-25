package com.simonrowe.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Message;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ArticleSourceTextProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit coverage of the generation flow. The insert-first dedup guard's happy path is here;
 * the concurrent race and the stale reclaim need a real MongoDB and live in
 * {@code ArticleSummaryConcurrencyTest}.
 */
@ExtendWith(MockitoExtension.class)
class ArticleSummaryServiceTest {

  private static final String MODEL = "test-model";
  private static final String ARTICLE_ID = "art-1";
  private static final Duration TIMEOUT = Duration.ofMinutes(3);

  // Comfortably over ArticleSourceTextProvider.HARD_MIN_SOURCE_CHARS (200).
  private static final String USABLE_SOURCE = "Substantive article body. ".repeat(30);

  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private ArticleSummaryRepository summaryRepository;
  @Mock private ArticleSourceTextProvider sourceTextProvider;
  @Mock private Ai ai;
  @Mock private MongoTemplate mongoTemplate;

  private PromptRunner promptRunner;
  private AssistantMessage assistantMessage;
  private ArticleSummaryService service;

  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    assistantMessage = mock(AssistantMessage.class);
    lenient().when(ai.withLlm(MODEL)).thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList())).thenReturn(assistantMessage);
    lenient().when(assistantMessage.getContent())
        .thenReturn("First paragraph.\n\nSecond paragraph.");
    lenient().when(summaryRepository.insert(any(ArticleSummary.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(summaryRepository.save(any(ArticleSummary.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    service = new ArticleSummaryService(
        articleRepository, summaryRepository, sourceTextProvider, ai, mongoTemplate,
        new SimpleMeterRegistry(), MODEL, TIMEOUT);
  }

  // ---------------------------------------------------------------- generation

  @Test
  void generatesAndStoresProseForFreshArticle() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.READY);
    assertThat(result.response().body())
        .isEqualTo("First paragraph.\n\nSecond paragraph.");
    assertThat(result.response().model()).isEqualTo(MODEL);
    assertThat(result.accepted()).isFalse();

    ArticleSummary stored = savedSummary();
    assertThat(stored.status()).isEqualTo(SummaryStatus.READY);
    assertThat(stored.sourceCharacterCount()).isEqualTo(USABLE_SOURCE.length());
    assertThat(stored.completedAt()).isNotNull();
  }

  @Test
  void promptCarriesTheArticleTitleSourceAndResolvedText() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);

    service.request(ARTICLE_ID);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent())
        .contains("Spring Boot 4 Released")
        .contains("InfoQ")
        .contains("Substantive article body.");
  }

  /**
   * The prompt requires neutral third person, 4-6 paragraphs, Markdown, no heading, and
   * no restatement of the title. Asserted so a later prompt edit that drops one of the
   * load-bearing instructions is caught.
   */
  @Test
  void promptStatesTheRegisterLengthAndFormattingConstraints() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);

    service.request(ARTICLE_ID);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    verify(promptRunner).respond(captor.capture());
    String prompt = captor.getValue().get(0).getContent();
    assertThat(prompt)
        .containsIgnoringCase("third person")
        .containsIgnoringCase("Markdown")
        .contains("4")
        .contains("6")
        .containsIgnoringCase("heading")
        .containsIgnoringCase("title");
  }

  // ---------------------------------------------------------------- source floor

  @Test
  void sourceTextUnderTheHardFloorFailsNonRetryablyWithoutCallingTheModel() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn("Only a feed snippet.");

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.FAILED);
    assertThat(result.response().failureCode())
        .isEqualTo(ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT);
    assertThat(result.response().retryable()).isFalse();
    verify(promptRunner, never()).respond(anyList());

    ArticleSummary stored = savedSummary();
    assertThat(stored.status()).isEqualTo(SummaryStatus.FAILED);
    assertThat(stored.retryable()).isFalse();
  }

  @Test
  void emptySourceTextFailsNonRetryablyWithoutCallingTheModel() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn("");

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().failureCode())
        .isEqualTo(ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT);
    verify(promptRunner, never()).respond(anyList());
  }

  // ---------------------------------------------------------------- model failure

  @Test
  void blankCompletionFailsRetryably() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);
    when(assistantMessage.getContent()).thenReturn("   ");

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().failureCode())
        .isEqualTo(ArticleSummaryFailure.MODEL_ERROR);
    assertThat(result.response().retryable()).isTrue();
    assertThat(savedSummary().retryable()).isTrue();
  }

  @Test
  void nullCompletionFailsRetryably() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);
    when(assistantMessage.getContent()).thenReturn(null);

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().failureCode())
        .isEqualTo(ArticleSummaryFailure.MODEL_ERROR);
    assertThat(result.response().retryable()).isTrue();
  }

  @Test
  void thrownModelCallFailsRetryablyRatherThanPropagating() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);
    when(promptRunner.respond(anyList()))
        .thenThrow(new RuntimeException("upstream 500"));

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().failureCode())
        .isEqualTo(ArticleSummaryFailure.MODEL_ERROR);
    assertThat(result.response().retryable()).isTrue();
  }

  // ---------------------------------------------------------------- missing article

  @Test
  void missingArticleIsNotFoundAndStoresNothing() {
    when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());

    assertThat(catchStatus(() -> service.request(ARTICLE_ID)))
        .isEqualTo(HttpStatus.NOT_FOUND);
    verify(summaryRepository, never()).insert(any(ArticleSummary.class));
    verify(promptRunner, never()).respond(anyList());
  }

  @Test
  void invisibleArticleIsNotFound() {
    when(articleRepository.findById(ARTICLE_ID))
        .thenReturn(Optional.of(article(false)));

    assertThat(catchStatus(() -> service.request(ARTICLE_ID)))
        .isEqualTo(HttpStatus.NOT_FOUND);
    verify(promptRunner, never()).respond(anyList());
  }

  // ---------------------------------------------------------------- no re-spend

  @Test
  void existingReadySummaryShortCircuitsWithNoModelCall() {
    visibleArticle();
    ArticleSummary ready = summary();
    ready.markReady("Existing prose.", "old-model", 5000, Instant.now());
    when(summaryRepository.findById(anyString())).thenReturn(Optional.of(ready));

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.READY);
    assertThat(result.response().body()).isEqualTo("Existing prose.");
    verify(promptRunner, never()).respond(anyList());
    verify(summaryRepository, never()).insert(any(ArticleSummary.class));
  }

  @Test
  void existingNonRetryableFailureIsReturnedUnchangedWithNoModelCall() {
    visibleArticle();
    ArticleSummary failed = summary();
    failed.markFailed(
        ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT, false, Instant.now());
    when(summaryRepository.findById(anyString())).thenReturn(Optional.of(failed));

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.FAILED);
    assertThat(result.response().failureCode())
        .isEqualTo(ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT);
    assertThat(result.response().retryable()).isFalse();
    verify(promptRunner, never()).respond(anyList());
    verify(summaryRepository, never()).save(any(ArticleSummary.class));
  }

  @Test
  void existingRetryableFailureDoesRegenerate() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);
    ArticleSummary failed = summary();
    failed.markFailed(ArticleSummaryFailure.MODEL_ERROR, true, Instant.now());
    when(summaryRepository.findById(anyString())).thenReturn(Optional.of(failed));

    ArticleSummaryService.RequestResult result = service.request(ARTICLE_ID);

    assertThat(result.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.READY);
    verify(promptRunner).respond(anyList());
  }

  // ---------------------------------------------------------------- id derivation

  /**
   * Recomputed independently here, so editing the prompt without bumping
   * {@code SUMMARY_FORMAT_VERSION} is caught: the constant feeds the id, and the id is the
   * dedup key that decides whether an existing summary is reused.
   */
  @Test
  void documentIdIsDerivedFromTheFormatVersionAndArticleId() {
    visibleArticle();
    when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);

    service.request(ARTICLE_ID);

    String expected = ArticleSummary.idFor(
        ArticleSummaryService.SUMMARY_FORMAT_VERSION, ARTICLE_ID);
    assertThat(savedSummary().id()).isEqualTo(expected);
  }

  @Test
  void differentFormatVersionsYieldDifferentIdsSoPromptBumpInvalidatesEverything() {
    assertThat(ArticleSummary.idFor("v1", ARTICLE_ID))
        .isNotEqualTo(ArticleSummary.idFor("v2", ARTICLE_ID));
  }

  // ---------------------------------------------------------------- status reads

  @Test
  void statusForArticleWithNoSummaryIsNotRequestedRatherThanFailure() {
    visibleArticle();
    when(summaryRepository.findById(anyString())).thenReturn(Optional.empty());

    ArticleSummaryResponse response = service.getStatus(ARTICLE_ID, null, 0);

    assertThat(response.state())
        .isEqualTo(ArticleSummaryResponse.PublicState.NOT_REQUESTED);
    assertThat(response.version()).isZero();
  }

  @Test
  void statusForMissingArticleIsNotFound() {
    when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());

    assertThat(catchStatus(() -> service.getStatus(ARTICLE_ID, null, 0)))
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void statusLongPollReturnsImmediatelyWhenTheVersionHasAlreadyMoved() {
    visibleArticle();
    ArticleSummary ready = summary();
    ready.markReady("Prose.", MODEL, 1000, Instant.now());
    when(summaryRepository.findById(anyString())).thenReturn(Optional.of(ready));

    Instant before = Instant.now();
    ArticleSummaryResponse response = service.getStatus(ARTICLE_ID, 1L, 25);

    assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(2));
    assertThat(response.state()).isEqualTo(ArticleSummaryResponse.PublicState.READY);
  }

  @Test
  void statusLongPollReturnsImmediatelyOnTerminalStateEvenWhenVersionMatches() {
    visibleArticle();
    ArticleSummary ready = summary();
    ready.markReady("Prose.", MODEL, 1000, Instant.now());
    when(summaryRepository.findById(anyString())).thenReturn(Optional.of(ready));

    Instant before = Instant.now();
    ArticleSummaryResponse response =
        service.getStatus(ARTICLE_ID, ready.version(), 25);

    assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(2));
    assertThat(response.state()).isEqualTo(ArticleSummaryResponse.PublicState.READY);
  }

  @Test
  void summarisedArticleIdsListsOnlyReadyOnes() {
    ArticleSummary ready = new ArticleSummary("s1", "art-ready", Instant.now());
    ready.markReady("Prose.", MODEL, 1000, Instant.now());
    when(summaryRepository.findByStatus(SummaryStatus.READY))
        .thenReturn(List.of(ready));

    assertThat(service.summarisedArticleIds()).containsExactly("art-ready");
  }

  // ---------------------------------------------------------------- helpers

  private void visibleArticle() {
    when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(article(true)));
  }

  private static AggregatedArticle article(final boolean visible) {
    return new AggregatedArticle(
        ARTICLE_ID, "Spring Boot 4 Released", "InfoQ", "https://infoq.com",
        "https://infoq.com/spring-boot-4", "Stored blurb.", "Stored content.",
        "Jane Doe", Instant.now(), Instant.now(), visible, null);
  }

  private static ArticleSummary summary() {
    return new ArticleSummary(
        ArticleSummary.idFor(ArticleSummaryService.SUMMARY_FORMAT_VERSION, ARTICLE_ID),
        ARTICLE_ID,
        Instant.now());
  }

  private ArticleSummary savedSummary() {
    ArgumentCaptor<ArticleSummary> captor = ArgumentCaptor.forClass(ArticleSummary.class);
    verify(summaryRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }

  private static HttpStatus catchStatus(final Runnable action) {
    try {
      action.run();
    } catch (ResponseStatusException ex) {
      return HttpStatus.valueOf(ex.getStatusCode().value());
    }
    throw new AssertionError("Expected a ResponseStatusException");
  }
}
