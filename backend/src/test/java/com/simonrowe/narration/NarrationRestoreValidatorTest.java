package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

class NarrationRestoreValidatorTest extends AbstractIntegrationTest {

  private static final byte[] MP3 = new byte[]{'I', 'D', '3', 4, 5, 6, 7, 8};
  private static final Path NARRATION_UPLOADS =
      Path.of("target/test-uploads/narrations");

  @Autowired private NarrationRepository repository;
  @Autowired private NarrationStorage storage;
  @Autowired private NarrationRestoreValidator validator;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  @AfterEach
  void clean() throws IOException {
    mongoTemplate.dropCollection("narrations");
    if (Files.exists(NARRATION_UPLOADS)) {
      try (var paths = Files.walk(NARRATION_UPLOADS)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  void keepsValidReadyAssetAndMakesMissingOrCorruptAssetsRetryable() {
    Narration valid = narration("valid");
    valid.markReady(storage.store("valid", MP3), Instant.now());
    repository.save(valid);
    Narration missing = narration("missing");
    missing.markReady(new NarrationStorage.StoredNarration(
        "/uploads/narrations/missing/narration.mp3", 8, "missing", 1),
        Instant.now());
    repository.save(missing);

    NarrationRestoreValidator.ReconciliationResult result = validator.reconcile();

    assertThat(result.ready()).isEqualTo(1);
    assertThat(result.retryable()).isEqualTo(1);
    assertThat(repository.findById("valid").orElseThrow().status())
        .isEqualTo(NarrationStatus.READY);
    Narration failed = repository.findById("missing").orElseThrow();
    assertThat(failed.status()).isEqualTo(NarrationStatus.FAILED);
    assertThat(failed.retryable()).isTrue();
    assertThat(failed.audioPath()).isNull();
  }

  @Test
  void normalizesSafeIncompleteWorkButPreservesAmbiguousOutcome() {
    Narration queued = narration("queued");
    repository.save(queued);
    Narration resumable = narration("resumable");
    resumable.claimed(Instant.now().plusSeconds(60), Instant.now());
    resumable.markProviderRequestStarted(Instant.now());
    resumable.markProviderOperation("operations/known", Instant.now());
    repository.save(resumable);
    Narration ambiguous = narration("ambiguous");
    ambiguous.claimed(Instant.now().plusSeconds(60), Instant.now());
    ambiguous.markProviderRequestStarted(Instant.now());
    repository.save(ambiguous);

    validator.reconcile();

    assertThat(repository.findById("queued").orElseThrow().status())
        .isEqualTo(NarrationStatus.FAILED);
    assertThat(repository.findById("resumable").orElseThrow().retryable()).isTrue();
    Narration preserved = repository.findById("ambiguous").orElseThrow();
    assertThat(preserved.status()).isEqualTo(NarrationStatus.PROCESSING);
    assertThat(preserved.retryable()).isFalse();
  }

  @Test
  void recreatesIndexesDroppedByRestore() {
    validator.ensureIndexes();

    assertThat(mongoTemplate.indexOps(Narration.class).getIndexInfo())
        .extracting(index -> index.getName())
        .contains("fingerprint", "blogId", "idx_narration_blog_updated",
            "idx_narration_status_lease");
  }

  private static Narration narration(final String id) {
    return new Narration(id, "blog-1", 100, "voice", "en-GB", "MP3",
        "narrations/" + id + ".mp3", Instant.now());
  }
}
