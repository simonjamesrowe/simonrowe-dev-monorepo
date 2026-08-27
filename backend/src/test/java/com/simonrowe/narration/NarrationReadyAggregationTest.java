package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The newest-{@code READY}-per-content-id reduction, against a real Mongo.
 *
 * <p>This is the half of {@code GET /api/narrations/ready} that a mock cannot verify. Narrations
 * are fingerprint-addressed, so one content id accumulates documents over its lifetime and
 * superseded ones are marked {@code STALE} rather than deleted — the whole point of the
 * aggregation is picking exactly one row out of that history, and never an unplayable one.
 */
class NarrationReadyAggregationTest extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-26T09:00:00Z");

  @Autowired
  private NarrationService narrationService;

  @Autowired
  private MongoTemplate mongoTemplate;

  /**
   * Documents, not the collection: dropping it would take the narration indexes with it, and the
   * Spring context — and therefore the Mongo container — is shared across integration tests.
   */
  @BeforeEach
  void clearNarrations() {
    mongoTemplate.remove(new Query(), Narration.class);
  }

  @Test
  void returnsOneRowPerContentIdWithItsAudioAndDuration() {
    save(ready("blog-a-v1", NarrationContentType.BLOG, "blog-a", 734, NOW));
    save(ready("blog-b-v1", NarrationContentType.BLOG, "blog-b", 412, NOW));

    List<ReadyNarration> ready = narrationService.readyNarrations(NarrationContentType.BLOG);

    assertThat(ready)
        .extracting(ReadyNarration::contentId, ReadyNarration::durationSeconds)
        .containsExactlyInAnyOrder(
            tuple("blog-a", 734L),
            tuple("blog-b", 412L));
    assertThat(ready).allSatisfy(row ->
        assertThat(row.audioUrl()).startsWith("/uploads/narrations/"));
  }

  /**
   * Two {@code READY} renders of the same post — an older one and a re-render — must collapse to
   * the newer. Sorting on {@code updatedAt} descending before the group is what does it.
   */
  @Test
  void keepsOnlyTheNewestReadyRowWhenOneContentIdHasSeveral() {
    save(ready("blog-a-old", NarrationContentType.BLOG, "blog-a", 100,
        NOW.minus(5, ChronoUnit.DAYS)));
    save(ready("blog-a-new", NarrationContentType.BLOG, "blog-a", 999, NOW));

    List<ReadyNarration> ready = narrationService.readyNarrations(NarrationContentType.BLOG);

    assertThat(ready).hasSize(1);
    assertThat(ready.getFirst().contentId()).isEqualTo("blog-a");
    assertThat(ready.getFirst().durationSeconds()).isEqualTo(999L);
    assertThat(ready.getFirst().audioUrl()).contains("blog-a-new");
  }

  /**
   * The realistic shape after a post is edited: the old audio is {@code STALE} and a new render is
   * {@code READY}. Only the {@code READY} row may be advertised.
   */
  @Test
  void neverReturnsTheStaleSiblingEvenWhenItIsNewest() {
    save(ready("blog-a-v1", NarrationContentType.BLOG, "blog-a", 300,
        NOW.minus(2, ChronoUnit.DAYS)));
    Narration superseded = ready("blog-a-v2", NarrationContentType.BLOG, "blog-a", 305, NOW);
    superseded.markStale(NOW);
    save(superseded);

    List<ReadyNarration> ready = narrationService.readyNarrations(NarrationContentType.BLOG);

    assertThat(ready).hasSize(1);
    // The v1 MP3 is still on disk and still playable, so offering it beats offering nothing.
    assertThat(ready.getFirst().audioUrl()).contains("blog-a-v1");
    assertThat(ready.getFirst().durationSeconds()).isEqualTo(300L);
  }

  @Test
  void neverReturnsQueuedProcessingFailedOrUncertainRows() {
    save(queued("q", NarrationContentType.BLOG, "blog-queued"));

    Narration processing = queued("p", NarrationContentType.BLOG, "blog-processing");
    processing.claimed(NOW.plusSeconds(600), NOW);
    save(processing);

    Narration failed = queued("f", NarrationContentType.BLOG, "blog-failed");
    failed.markFailed("PROVIDER_ERROR", true, NOW);
    save(failed);

    Narration uncertain = queued("u", NarrationContentType.BLOG, "blog-uncertain");
    uncertain.markUncertain("UNVERIFIED_OUTPUT", NOW);
    save(uncertain);

    assertThat(narrationService.readyNarrations(NarrationContentType.BLOG)).isEmpty();
  }

  @Test
  void isolatesContentTypesFromEachOther() {
    save(ready("blog-a-v1", NarrationContentType.BLOG, "blog-a", 734, NOW));
    save(ready("summary-9-v1", NarrationContentType.ARTICLE_SUMMARY, "article-9", 180, NOW));

    assertThat(narrationService.readyNarrations(NarrationContentType.BLOG))
        .extracting(ReadyNarration::contentId)
        .containsExactly("blog-a");
    // For ARTICLE_SUMMARY the contentId is the aggregated article id, not the summary id.
    assertThat(narrationService.readyNarrations(NarrationContentType.ARTICLE_SUMMARY))
        .extracting(ReadyNarration::contentId)
        .containsExactly("article-9");
  }

  @Test
  void returnsAnEmptyListWhenNothingHasBeenNarrated() {
    assertThat(narrationService.readyNarrations(NarrationContentType.BLOG)).isEmpty();
    assertThat(narrationService.readyNarrations(NarrationContentType.ARTICLE_SUMMARY)).isEmpty();
  }

  private void save(final Narration narration) {
    mongoTemplate.save(narration);
  }

  private Narration queued(
      final String id,
      final NarrationContentType contentType,
      final String contentId
  ) {
    return new Narration(
        id, contentType, contentId, 1200, "en-GB-Standard-B", "en-GB", "MP3",
        "narrations/" + id + ".mp3", NOW);
  }

  private Narration ready(
      final String id,
      final NarrationContentType contentType,
      final String contentId,
      final long durationSeconds,
      final Instant updatedAt
  ) {
    Narration narration = queued(id, contentType, contentId);
    narration.markReady(new NarrationStorage.StoredNarration(
        "/uploads/narrations/" + id + "/narration.mp3", 4096, "checksum-" + id,
        durationSeconds), updatedAt);
    return narration;
  }
}
