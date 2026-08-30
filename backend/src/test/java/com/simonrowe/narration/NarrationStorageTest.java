package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NarrationStorageTest {

  private static final byte[] MP3 = new byte[]{'I', 'D', '3', 4, 5, 6, 7, 8};

  @TempDir
  private Path uploads;

  @Test
  void validatesAndAtomicallyStoresMp3WithIntegrityMetadata() {
    NarrationStorage storage = new NarrationStorage(uploads.toString());

    NarrationStorage.StoredNarration stored = storage.store("narration-1", MP3);

    assertThat(stored.publicPath())
        .isEqualTo("/uploads/narrations/narration-1/narration.mp3");
    assertThat(stored.fileSize()).isEqualTo(MP3.length);
    assertThat(stored.checksumSha256()).hasSize(64);
    assertThat(stored.durationSeconds()).isEqualTo(1);
    assertThat(uploads.resolve("narrations/narration-1/narration.mp3"))
        .exists()
        .hasBinaryContent(MP3);
    assertThat(uploads.resolve("narrations/narration-1/.work")).doesNotExist();
  }

  @Test
  void rejectsNonMp3AndDetectsCorruption() throws Exception {
    NarrationStorage storage = new NarrationStorage(uploads.toString());
    assertThatThrownBy(() -> storage.store("bad", new byte[]{1, 2, 3, 4}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not an MP3");

    Narration narration = narration("valid");
    narration.markReady(storage.store("valid", MP3), Instant.now());
    assertThat(storage.isValid(narration)).isTrue();

    Files.write(uploads.resolve("narrations/valid/narration.mp3"),
        new byte[]{'I', 'D', '3', 9});
    assertThat(storage.isValid(narration)).isFalse();
  }

  @Test
  void deletionWorksBeforePublicMetadataHasBeenAssigned() {
    NarrationStorage storage = new NarrationStorage(uploads.toString());
    storage.store("orphan", MP3);

    storage.delete("orphan");

    assertThat(uploads.resolve("narrations/orphan/narration.mp3")).doesNotExist();
  }

  @Test
  void deleteAllRemovesEveryAudioFileAndItsDirectoryAndIsIdempotent() {
    NarrationStorage storage = new NarrationStorage(uploads.toString());
    storage.store("narration-1", MP3);
    storage.store("narration-2", MP3);

    assertThat(storage.deleteAll()).isEqualTo(2);
    assertThat(uploads.resolve("narrations")).doesNotExist();

    // Replaying the purge finds nothing left to remove rather than failing.
    assertThat(storage.deleteAll()).isZero();
  }

  @Test
  void deleteAllTreatsMissingNarrationsDirectoryAsNothingToDo() {
    assertThat(new NarrationStorage(uploads.toString()).deleteAll()).isZero();
  }

  private static Narration narration(final String id) {
    return new Narration(id, NarrationContentType.BLOG, "blog-1", 10, "voice", "en-GB", "MP3",
        "narrations/" + id + ".mp3", Instant.now());
  }
}
