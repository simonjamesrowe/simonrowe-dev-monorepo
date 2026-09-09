package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On-disk storage for the original PDFs, so an answer can link to the real letter rather than
 * only paraphrasing it.
 */
class SchoolAttachmentStoreTest {

  private static final byte[] PDF = "%PDF-1.7 content".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("bytes round-trip, and the directory is created on demand")
  void roundTrips(@TempDir final Path tmp) {
    // The directory deliberately does not have to exist: the path is configurable and a fresh
    // deployment has never written one.
    final SchoolAttachmentStore store =
        new SchoolAttachmentStore(tmp.resolve("does-not-exist-yet").toString());

    assertThat(store.store("abc123", PDF)).isTrue();
    assertThat(store.has("abc123")).isTrue();
    assertThat(store.read("abc123")).contains(PDF);
  }

  @Test
  @DisplayName("nothing stored means empty, not an error")
  void missingFileReadsEmpty(@TempDir final Path tmp) {
    final SchoolAttachmentStore store = new SchoolAttachmentStore(tmp.toString());

    assertThat(store.has("nope")).isFalse();
    assertThat(store.read("nope")).isEmpty();
  }

  @Test
  @DisplayName("an empty or null body is refused rather than written as a zero-byte file")
  void refusesEmptyBodies(@TempDir final Path tmp) {
    // A zero-byte PDF would pass `has` and then serve a broken download.
    final SchoolAttachmentStore store = new SchoolAttachmentStore(tmp.toString());

    assertThat(store.store("abc123", new byte[0])).isFalse();
    assertThat(store.store("abc123", null)).isFalse();
    assertThat(store.has("abc123")).isFalse();
  }

  @Test
  @DisplayName("a traversing id is rejected loudly, not silently written outside the store")
  void refusesPathTraversal(@TempDir final Path tmp) {
    // Ids are hex digests today, so this is defence against a future caller rather than a live
    // hole - which is exactly when it is cheapest to pin. It throws rather than returning
    // false deliberately: a caller passing a traversing id has a bug, and quietly answering
    // "not stored" would hide it.
    final SchoolAttachmentStore store = new SchoolAttachmentStore(tmp.resolve("root").toString());

    assertThatThrownBy(() -> store.store("../escaped", PDF))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("escapes the store");
    assertThatThrownBy(() -> store.read("../escaped"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(java.nio.file.Files.exists(tmp.resolve("escaped.pdf"))).isFalse();
  }

  @Test
  @DisplayName("storing twice overwrites rather than duplicating")
  void storeOverwrites(@TempDir final Path tmp) {
    final SchoolAttachmentStore store = new SchoolAttachmentStore(tmp.toString());
    store.store("abc123", PDF);

    final byte[] replacement = "%PDF-1.7 newer".getBytes(StandardCharsets.UTF_8);
    assertThat(store.store("abc123", replacement)).isTrue();
    assertThat(store.read("abc123")).contains(replacement);
  }
}
