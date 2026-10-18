package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The queries behind {@link NarrationTimingsUpgradeSweep}, against a real Mongo: which
 * narration is re-made next, and the conditional swap that must never clobber a narration
 * that moved on while the re-make ran.
 */
class NarrationTimingsUpgradeQueriesTest extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

  @Autowired private NarrationService narrationService;
  @Autowired private NarrationRepository repository;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void clearNarrations() {
    mongoTemplate.remove(new Query(), Narration.class);
  }

  @Test
  void picksTheNewestReadyNarrationWithoutTimings() {
    repository.save(ready("older", NOW.minus(2, ChronoUnit.DAYS), null));
    repository.save(ready("newer", NOW.minus(1, ChronoUnit.DAYS), null));
    repository.save(ready("timed", NOW, "/uploads/narrations/timed/timings-a.json"));
    Narration queued = narration("queued");
    repository.save(queued);

    assertThat(narrationService.nextWithoutTimings(3)).get()
        .extracting(Narration::id).isEqualTo("newer");
  }

  /** Documents written before these fields existed have no timingsPath and no attempts. */
  @Test
  void matchesDocumentsWrittenBeforeTheFieldsExisted() {
    repository.save(ready("legacy", NOW, null));
    mongoTemplate.getCollection("narrations").updateOne(
        new Document("_id", "legacy"),
        new Document("$unset", new Document("timingsPath", "").append("timingsAttempts", "")));

    assertThat(narrationService.nextWithoutTimings(3)).get()
        .extracting(Narration::id).isEqualTo("legacy");
  }

  @Test
  void skipsNarrationTheUpgradeHasGivenUpOn() {
    Narration narration = ready("failing", NOW, null);
    repository.save(narration);
    for (int i = 0; i < 3; i++) {
      narrationService.recordFailedUpgrade(narration, true, NOW);
    }

    assertThat(narrationService.nextWithoutTimings(3)).isEmpty();
    assertThat(repository.findById("failing").orElseThrow().resynthesizedAt()).isEqualTo(NOW);
  }

  @Test
  void swapsInTheReMadeAudioAndRetiresTheOld() {
    Narration narration = ready("swap", NOW, null);
    repository.save(narration);

    boolean swapped = narrationService.replaceAudio(narration, timedStored("swap"), NOW);

    assertThat(swapped).isTrue();
    Narration after = repository.findById("swap").orElseThrow();
    assertThat(after.status()).isEqualTo(NarrationStatus.READY);
    assertThat(after.audioPath()).isEqualTo("/uploads/narrations/swap/audio-b.mp3");
    assertThat(after.timingsPath()).isEqualTo("/uploads/narrations/swap/timings-b.json");
    assertThat(after.checksumSha256()).isEqualTo("new-checksum");
    assertThat(after.durationSeconds()).isEqualTo(188L);
    assertThat(after.retiredAudioPath()).isEqualTo("/uploads/narrations/swap/narration.mp3");
    assertThat(after.resynthesizedAt()).isEqualTo(NOW);
    assertThat(after.version()).isEqualTo(narration.version() + 1);
  }

  /** Reuse bumps the version on every Listen, so the swap must not depend on it. */
  @Test
  void swapsEvenAfterTheNarrationWasReusedMeanwhile() {
    Narration narration = ready("popular", NOW, null);
    repository.save(narration);
    mongoTemplate.getCollection("narrations").updateOne(new Document("_id", "popular"),
        new Document("$inc", new Document("version", 5).append("reuseCount", 5)));

    assertThat(narrationService.replaceAudio(narration, timedStored("popular"), NOW)).isTrue();
  }

  @Test
  void leavesNarrationThatWentStaleMeanwhile() {
    Narration narration = ready("stale", NOW, null);
    repository.save(narration);
    Narration current = repository.findById("stale").orElseThrow();
    current.markStale(NOW);
    repository.save(current);

    assertThat(narrationService.replaceAudio(narration, timedStored("stale"), NOW)).isFalse();
    assertThat(repository.findById("stale").orElseThrow().audioPath()).isNull();
  }

  @Test
  void leavesNarrationWhoseAudioWasRegeneratedMeanwhile() {
    Narration narration = ready("regenerated", NOW, null);
    repository.save(narration);
    Narration current = repository.findById("regenerated").orElseThrow();
    current.markReady(new NarrationStorage.StoredNarration(
        "/uploads/narrations/regenerated/narration.mp3", 9, "other-checksum", 9), NOW);
    repository.save(current);

    assertThat(narrationService.replaceAudio(narration, timedStored("regenerated"), NOW))
        .isFalse();
  }

  @Test
  void findsRetiredAudioOnlyOnceItHasBeenRetiredLongEnough() {
    Narration narration = ready("retire", NOW, null);
    repository.save(narration);
    narrationService.replaceAudio(narration, timedStored("retire"), NOW);

    assertThat(narrationService.retiredBefore(NOW)).isEmpty();
    assertThat(narrationService.retiredBefore(NOW.plusSeconds(1)))
        .extracting(Narration::id).containsExactly("retire");

    narrationService.deleteRetired(repository.findById("retire").orElseThrow());

    Narration after = repository.findById("retire").orElseThrow();
    assertThat(after.retiredAudioPath()).isNull();
    assertThat(after.retiredAt()).isNull();
    assertThat(after.audioPath()).isEqualTo("/uploads/narrations/retire/audio-b.mp3");
    assertThat(narrationService.retiredBefore(NOW.plusSeconds(1))).isEmpty();
  }

  private static NarrationStorage.StoredNarration timedStored(final String id) {
    return new NarrationStorage.StoredNarration(
        "/uploads/narrations/" + id + "/audio-b.mp3", 750_000, "new-checksum", 188,
        "/uploads/narrations/" + id + "/timings-b.json");
  }

  private static Narration ready(
      final String id, final Instant completedAt, final String timingsPath) {
    Narration narration = narration(id);
    narration.claimed(completedAt.plusSeconds(60), completedAt);
    narration.markReady(new NarrationStorage.StoredNarration(
        "/uploads/narrations/" + id + "/narration.mp3", 690_000, "old-checksum", 172,
        timingsPath), completedAt);
    return narration;
  }

  private static Narration narration(final String id) {
    return new Narration(id, NarrationContentType.ARTICLE_SUMMARY, "article-" + id, 2700,
        "voice", "en-GB", "MP3", "narrations/" + id + ".mp3", NOW);
  }
}
