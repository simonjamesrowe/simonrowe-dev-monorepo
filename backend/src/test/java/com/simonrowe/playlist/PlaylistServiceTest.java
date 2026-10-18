package com.simonrowe.playlist;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.narration.NarrationContentType;
import com.simonrowe.narration.NarrationResponse;
import com.simonrowe.narration.NarrationService;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryReadyEvent;
import com.simonrowe.summary.ArticleSummaryService;
import com.simonrowe.summary.SummaryRequestPublisher;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * What happens when a summary on the playlist has just been written. Mocked rather than run
 * against Mongo because the outcome is a call to text-to-speech, which is switched off in the
 * integration context and so leaves nothing there to observe.
 */
@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

  private static final String ARTICLE_ID = "a-1";
  private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");

  @Mock private MongoTemplate mongoTemplate;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private BlogRepository blogRepository;
  @Mock private ArticleSummaryService summaryService;
  @Mock private SummaryRequestPublisher summaryRequestPublisher;
  @Mock private NarrationService narrationService;

  private PlaylistService service;

  @BeforeEach
  void setUp() {
    service = new PlaylistService(mongoTemplate, articleRepository, blogRepository,
        summaryService, summaryRequestPublisher, narrationService);
    ArticleSummary summary = new ArticleSummary("s-1", ARTICLE_ID, NOW);
    summary.markReady("Written.", "model", 4000, NOW);
    lenient().when(summaryService.findFor(ARTICLE_ID)).thenReturn(Optional.of(summary));
    lenient().when(narrationService.request(any(), anyString()))
        .thenReturn(new NarrationService.RequestResult(NarrationResponse.notRequested(), true));
    settings(false);
  }

  @Test
  void voicesAnItemSomeoneAskedToHear() {
    item(PlaylistItemStatus.QUEUED, true);

    service.onSummaryReady(new ArticleSummaryReadyEvent(ARTICLE_ID));

    verify(narrationService).request(NarrationContentType.ARTICLE_SUMMARY, ARTICLE_ID);
  }

  @Test
  void leavesAnItemNobodyAskedForWhenAutomaticAudioIsOff() {
    item(PlaylistItemStatus.QUEUED, false);

    service.onSummaryReady(new ArticleSummaryReadyEvent(ARTICLE_ID));

    verify(narrationService, never()).request(any(), anyString());
  }

  @Test
  void voicesEverythingWhenAutomaticAudioIsOn() {
    item(PlaylistItemStatus.QUEUED, false);
    settings(true);

    service.onSummaryReady(new ArticleSummaryReadyEvent(ARTICLE_ID));

    verify(narrationService).request(NarrationContentType.ARTICLE_SUMMARY, ARTICLE_ID);
  }

  @Test
  void leavesSomethingAlreadyHeardAlone() {
    item(PlaylistItemStatus.LISTENED, true);

    service.onSummaryReady(new ArticleSummaryReadyEvent(ARTICLE_ID));

    verify(narrationService, never()).request(any(), anyString());
  }

  @Test
  void ignoresSummariesOfArticlesNotOnThePlaylist() {
    service.onSummaryReady(new ArticleSummaryReadyEvent(ARTICLE_ID));

    verify(narrationService, never()).request(any(), anyString());
  }

  /** The summary is already saved; nothing here may undo that by throwing. */
  @Test
  void neverThrows() {
    item(PlaylistItemStatus.QUEUED, true);
    when(narrationService.request(any(), anyString())).thenThrow(new IllegalStateException("down"));

    assertThatCode(() -> service.onSummaryReady(new ArticleSummaryReadyEvent(ARTICLE_ID)))
        .doesNotThrowAnyException();
  }

  private void item(final PlaylistItemStatus status, final boolean voiceRequested) {
    when(mongoTemplate.findById("ARTICLE_SUMMARY:" + ARTICLE_ID, PlaylistItem.class))
        .thenReturn(new PlaylistItem("ARTICLE_SUMMARY:" + ARTICLE_ID,
            PlaylistContentType.ARTICLE_SUMMARY, ARTICLE_ID, status, 0.0,
            PlaylistSource.SUMMARISED, voiceRequested, null, NOW, NOW, null));
  }

  private void settings(final boolean autoVoice) {
    lenient().when(mongoTemplate.findById(PlaylistSettings.SINGLETON_ID, PlaylistSettings.class))
        .thenReturn(new PlaylistSettings(PlaylistSettings.SINGLETON_ID, autoVoice));
  }
}
