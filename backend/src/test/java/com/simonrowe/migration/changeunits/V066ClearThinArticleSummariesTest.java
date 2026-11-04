package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.narration.NarrationContentType;
import com.simonrowe.narration.NarrationService;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryFailure;
import com.simonrowe.summary.ArticleSummaryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Survivor selection on real documents. The rows are saved through {@link ArticleSummary} and
 * its repository, not as hand-written BSON, so a renamed field fails here rather than making
 * the unit silently match nothing. {@code V066ClearThinArticleSummariesBootTest} proves it
 * runs at boot.
 */
class V066ClearThinArticleSummariesTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ArticleSummaryRepository summaries;

  private final NarrationService narrationService = mock(NarrationService.class);
  private final V066ClearThinArticleSummaries changeUnit = new V066ClearThinArticleSummaries();

  @BeforeEach
  @AfterEach
  void clear() {
    summaries.deleteAll();
  }

  private void ready(final String articleId, final int sourceCharacters) {
    ArticleSummary summary = new ArticleSummary("s-" + articleId, articleId, Instant.now());
    summary.markReady("Body.", "gpt-6-luna", sourceCharacters, Instant.now());
    summaries.save(summary);
  }

  private void failed(final String articleId, final String code, final boolean retryable) {
    ArticleSummary summary = new ArticleSummary("s-" + articleId, articleId, Instant.now());
    summary.markFailed(code, retryable, Instant.now());
    summaries.save(summary);
  }

  private void generating(final String articleId) {
    summaries.save(new ArticleSummary("s-" + articleId, articleId, Instant.now()));
  }

  private List<String> remaining() {
    return summaries.findAll().stream().map(ArticleSummary::articleId).sorted().toList();
  }

  @Test
  void deletesSourceStarvedFailuresAndSummariesWrittenFromUnderFiveHundredCharacters() {
    failed("scaling-git", ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT, false);
    ready("habitat", 211);
    ready("edge", 499);
    ready("full", 12_000);
    ready("exactly-at-floor", 500);
    failed("model-error", ArticleSummaryFailure.MODEL_ERROR, true);
    generating("in-progress");

    changeUnit.execution(mongoTemplate, narrationService);

    assertThat(remaining())
        .containsExactly("exactly-at-floor", "full", "in-progress", "model-error");
  }

  @Test
  void invalidatesTheNarrationsOfDeletedReadySummariesOnlyOnceTheSummaryIsGone() {
    failed("scaling-git", ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT, false);
    ready("habitat", 211);
    ready("full", 12_000);
    List<String> summaryGoneWhenInvalidated = new ArrayList<>();
    doAnswer(call -> {
      String articleId = call.getArgument(1);
      if (summaries.findByArticleId(articleId).isEmpty()) {
        summaryGoneWhenInvalidated.add(articleId);
      }
      return null;
    }).when(narrationService).invalidate(
        eq(NarrationContentType.ARTICLE_SUMMARY), anyString());

    changeUnit.execution(mongoTemplate, narrationService);

    verify(narrationService).invalidate(NarrationContentType.ARTICLE_SUMMARY, "habitat");
    verify(narrationService, never())
        .invalidate(NarrationContentType.ARTICLE_SUMMARY, "scaling-git");
    verify(narrationService, never()).invalidate(NarrationContentType.ARTICLE_SUMMARY, "full");
    assertThat(summaryGoneWhenInvalidated).containsExactly("habitat");
  }

  @Test
  void runningTwiceDeletesNothingMore() {
    ready("habitat", 211);
    ready("full", 12_000);
    changeUnit.execution(mongoTemplate, narrationService);

    changeUnit.execution(mongoTemplate, narrationService);

    assertThat(remaining()).containsExactly("full");
    verify(narrationService).invalidate(NarrationContentType.ARTICLE_SUMMARY, "habitat");
  }
}
