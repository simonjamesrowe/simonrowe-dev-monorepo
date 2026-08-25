package com.simonrowe.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ArticleSourceTextProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The insert-first dedup guard and the stale reclaim, against a real MongoDB.
 *
 * <p>These two properties are the whole reason the design can be synchronous: without the
 * unique {@code _id} and the timeout-guarded conditional reclaim, a synchronous design
 * would double-spend on every concurrent request and strand every crashed one.
 */
@TestPropertySource(properties = "aggregation.summary.generation-timeout=3m")
class ArticleSummaryConcurrencyTest extends AbstractIntegrationTest {

  private static final String ARTICLE_ID = "concurrency-article-1";
  private static final String USABLE_SOURCE = "Substantive article body. ".repeat(30);

  @MockitoBean private ArticleSourceTextProvider sourceTextProvider;

  @Autowired private AggregatedArticleRepository articleRepository;
  @Autowired private ArticleSummaryRepository summaryRepository;
  @Autowired private ArticleSummaryService summaryService;
  @Autowired private MongoTemplate mongoTemplate;

  private PromptRunner promptRunner;

  @BeforeEach
  void setUp() {
    summaryRepository.deleteAll();
    promptRunner = mock(PromptRunner.class);
    AssistantMessage message = mock(AssistantMessage.class);
    lenient().when(ai.withLlm(anyString())).thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList())).thenReturn(message);
    lenient().when(message.getContent()).thenReturn("Generated prose.");
    lenient().when(sourceTextProvider.sourceTextFor(any())).thenReturn(USABLE_SOURCE);
    articleRepository.save(article());
  }

  @AfterEach
  void cleanUp() {
    summaryRepository.deleteAll();
    articleRepository.deleteById(ARTICLE_ID);
  }

  @Test
  void simultaneousRequestsProduceExactlyOneModelCallAndOneDocument() throws Exception {
    // The model call blocks until every thread has arrived, so the losers are genuinely
    // racing the winner rather than arriving after it has finished.
    CountDownLatch started = new CountDownLatch(20);
    CountDownLatch release = blockingModelCall();

    List<ArticleSummaryService.RequestResult> results =
        runConcurrently(20, started, release);

    assertThat(results).hasSize(20);
    verify(promptRunner, times(1)).respond(anyList());
    assertThat(summaryRepository.count()).isEqualTo(1);

    // Every caller ends up in one of exactly two honest places: holding the finished
    // summary, or told to poll for it. Which one depends on whether they arrived before or
    // after the winner finished, so the split is not asserted — one model call and one
    // document is the invariant that matters.
    assertThat(results).allSatisfy(r -> assertThat(r.response().state()).isIn(
        ArticleSummaryResponse.PublicState.READY,
        ArticleSummaryResponse.PublicState.GENERATING));
    assertThat(results).anySatisfy(r -> assertThat(r.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.READY));
  }

  @Test
  void everyLoserIsToldToPollRatherThanTreatingItsResponseAsFinal() throws Exception {
    CountDownLatch started = new CountDownLatch(10);
    CountDownLatch release = blockingModelCall();

    List<ArticleSummaryService.RequestResult> results =
        runConcurrently(10, started, release);

    results.stream()
        .filter(r -> r.response().state()
            == ArticleSummaryResponse.PublicState.GENERATING)
        .forEach(r -> assertThat(r.accepted())
            .as("a caller that lost the race must be told to poll")
            .isTrue());
  }

  @Test
  void summaryStuckGeneratingPastTheTimeoutIsReclaimedAndRegenerated() {
    ArticleSummary abandoned = insertGenerating();
    ageUpdatedAt(abandoned.id(), Duration.ofMinutes(10));

    ArticleSummaryService.RequestResult result = summaryService.request(ARTICLE_ID);

    assertThat(result.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.READY);
    verify(promptRunner, times(1)).respond(anyList());
    assertThat(summaryRepository.findById(abandoned.id()).orElseThrow().version())
        .isGreaterThan(abandoned.version());
  }

  @Test
  void summaryThatOnlyJustStartedGeneratingIsNotReclaimed() {
    insertGenerating();

    ArticleSummaryService.RequestResult result = summaryService.request(ARTICLE_ID);

    assertThat(result.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.GENERATING);
    assertThat(result.accepted()).isTrue();
    verify(promptRunner, never()).respond(anyList());
  }

  /**
   * The reclaim filter is guarded on {@code updatedAt} as well as {@code status}. Guarding
   * on status alone would let both reclaimers match the same document and both pay for a
   * model call.
   */
  @Test
  void twoReclaimersOfTheSameStaleSummaryYieldExactlyOneWinner() throws Exception {
    ArticleSummary abandoned = insertGenerating();
    ageUpdatedAt(abandoned.id(), Duration.ofMinutes(10));

    CountDownLatch started = new CountDownLatch(8);
    CountDownLatch release = blockingModelCall();

    List<ArticleSummaryService.RequestResult> results =
        runConcurrently(8, started, release);

    verify(promptRunner, times(1)).respond(anyList());
    assertThat(summaryRepository.count()).isEqualTo(1);
    assertThat(results.stream()
        .filter(r -> r.response().state() == ArticleSummaryResponse.PublicState.READY)
        .count()).isEqualTo(1);
  }

  @Test
  void readySummaryIsNeverRegeneratedNoMatterHowManyCallersArrive() throws Exception {
    summaryService.request(ARTICLE_ID);

    List<ArticleSummaryService.RequestResult> results =
        runConcurrently(20, null, null);

    verify(promptRunner, times(1)).respond(anyList());
    assertThat(results).allSatisfy(r -> assertThat(r.response().state())
        .isEqualTo(ArticleSummaryResponse.PublicState.READY));
  }

  /**
   * Fires {@code callers} concurrent requests and collects their outcomes.
   *
   * <p>Uses {@code submit} rather than {@code invokeAll}: {@code invokeAll} blocks until
   * every task has finished, which would mean releasing the latch only after the model call
   * it is supposed to be holding open had already timed out. Submitting first, then
   * releasing, is what makes the race window real.
   *
   * @param callers how many concurrent requests to make
   * @param started counted down by each caller as it enters, so the test knows when they
   *     are all in flight; null when no gate is needed
   * @param release blocks the model call until the test releases it; null when the model
   *     should return immediately
   */
  private List<ArticleSummaryService.RequestResult> runConcurrently(
      final int callers,
      final CountDownLatch started,
      final CountDownLatch release
  ) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ArticleSummaryService.RequestResult>> futures =
          IntStream.range(0, callers)
              .mapToObj(ignored -> executor.submit(() -> {
                if (started != null) {
                  started.countDown();
                }
                return summaryService.request(ARTICLE_ID);
              }))
              .toList();

      if (started != null) {
        assertThat(started.await(10, TimeUnit.SECONDS))
            .as("every caller should have started")
            .isTrue();
      }
      if (release != null) {
        release.countDown();
      }

      List<ArticleSummaryService.RequestResult> results = new java.util.ArrayList<>();
      for (Future<ArticleSummaryService.RequestResult> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    }
  }

  /**
   * Makes the model call block until the returned latch is released, so the winner is still
   * mid-generation while the other callers race it.
   */
  private CountDownLatch blockingModelCall() {
    CountDownLatch release = new CountDownLatch(1);
    when(promptRunner.respond(anyList())).thenAnswer(invocation -> {
      assertThat(release.await(20, TimeUnit.SECONDS))
          .as("the test should release the model call")
          .isTrue();
      AssistantMessage message = mock(AssistantMessage.class);
      when(message.getContent()).thenReturn("Generated prose.");
      return message;
    });
    return release;
  }

  private ArticleSummary insertGenerating() {
    ArticleSummary summary = new ArticleSummary(
        ArticleSummary.idFor(ArticleSummaryService.SUMMARY_FORMAT_VERSION, ARTICLE_ID),
        ARTICLE_ID,
        Instant.now());
    return summaryRepository.insert(summary);
  }

  /** Backdates updatedAt so the document looks abandoned, without waiting three minutes. */
  private void ageUpdatedAt(final String id, final Duration age) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("updatedAt", Instant.now().minus(age)),
        ArticleSummary.class);
  }

  private static AggregatedArticle article() {
    return new AggregatedArticle(
        ARTICLE_ID, "Spring Boot 4 Released", "InfoQ", "https://infoq.com",
        "https://infoq.com/concurrency-" + ARTICLE_ID, "Stored blurb.",
        "Stored content.", "Jane Doe", Instant.now(), Instant.now(), true, null);
  }
}
