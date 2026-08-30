package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.narration.NarrationStorage;
import java.nio.file.Path;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Exercises the narration purge against a real MongoDB and a temporary uploads directory.
 * Mongock is disabled in tests, so the change unit is driven directly.
 */
class V030PurgeNarrationsForVoiceChangeTest extends AbstractIntegrationTest {

  private static final byte[] MP3 = new byte[]{'I', 'D', '3', 4, 5, 6, 7, 8};

  @Autowired
  private MongoTemplate mongoTemplate;

  @TempDir
  private Path uploads;

  private final V030PurgeNarrationsForVoiceChange changeUnit =
      new V030PurgeNarrationsForVoiceChange();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.getCollection(V030PurgeNarrationsForVoiceChange.COLLECTION).drop();
  }

  @Test
  void removesEveryNarrationDocumentAndAudioFile() {
    NarrationStorage storage = new NarrationStorage(uploads.toString());
    storage.store("narration-1", MP3);
    storage.store("narration-2", MP3);
    mongoTemplate.getCollection(V030PurgeNarrationsForVoiceChange.COLLECTION)
        .insertMany(List.of(
            narration("narration-1", "blog-1", "READY"),
            narration("narration-2", "article-1", "STALE")));

    changeUnit.execution(mongoTemplate, storage);

    assertThat(mongoTemplate.getCollection(V030PurgeNarrationsForVoiceChange.COLLECTION)
        .countDocuments()).isZero();
    assertThat(uploads.resolve("narrations")).doesNotExist();
  }

  @Test
  void leavesTheCollectionsIndexesInPlaceSoNarrationKeepsWorking() {
    NarrationStorage storage = new NarrationStorage(uploads.toString());
    V021GeneraliseNarrationContentType.createIndexes(mongoTemplate);
    mongoTemplate.getCollection(V030PurgeNarrationsForVoiceChange.COLLECTION)
        .insertOne(narration("narration-1", "blog-1", "READY"));

    changeUnit.execution(mongoTemplate, storage);

    assertThat(mongoTemplate
        .indexOps(V030PurgeNarrationsForVoiceChange.COLLECTION)
        .getIndexInfo())
        .extracting(info -> info.getName())
        .contains("idx_narration_content_updated");
  }

  @Test
  void replayingThePurgeChangesNothing() {
    NarrationStorage storage = new NarrationStorage(uploads.toString());
    storage.store("narration-1", MP3);
    mongoTemplate.getCollection(V030PurgeNarrationsForVoiceChange.COLLECTION)
        .insertOne(narration("narration-1", "blog-1", "READY"));

    changeUnit.execution(mongoTemplate, storage);
    changeUnit.execution(mongoTemplate, storage);

    assertThat(mongoTemplate.getCollection(V030PurgeNarrationsForVoiceChange.COLLECTION)
        .countDocuments()).isZero();
    assertThat(uploads.resolve("narrations")).doesNotExist();
  }

  private static Document narration(
      final String id, final String contentId, final String status) {
    return new Document("_id", id)
        .append("contentType", "BLOG")
        .append("contentId", contentId)
        .append("fingerprint", id)
        .append("status", status)
        .append("voiceName", "en-GB-Chirp3-HD-Charon")
        .append("languageCode", "en-GB")
        .append("audioEncoding", "MP3")
        .append("audioPath", "/uploads/narrations/" + id + "/narration.mp3");
  }
}
