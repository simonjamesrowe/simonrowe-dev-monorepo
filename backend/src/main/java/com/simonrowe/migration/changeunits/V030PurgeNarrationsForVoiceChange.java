package com.simonrowe.migration.changeunits;

import com.simonrowe.narration.NarrationStorage;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Removes every stored narration — both the {@code narrations} documents and the MP3
 * files under {@code uploads/narrations} — after the text-to-speech voice changed from
 * {@code en-GB-Chirp3-HD-Charon} to {@code en-AU-Chirp3-HD-Achird}.
 *
 * <p>This is a correctness fix, not housekeeping. A narration's {@code _id} is a
 * fingerprint over the script text <em>and</em> the voice settings, so after the voice
 * change no detail page can ever look an existing narration up again — but
 * {@code NarrationService.readyNarrations}, which backs the Listen button on the blog and
 * news listings, matches only on {@code contentType} and {@code status: READY} and takes
 * the newest row per {@code contentId}. It has no notion of the current voice. Left in
 * place, every listing would keep serving the old British audio indefinitely while detail
 * pages generated Australian audio beside it.
 *
 * <p>Deletes rather than marking {@code STALE}: the corpus is being replaced wholesale, so
 * retaining the rows would keep dead metadata in MongoDB and in every future backup. One
 * accepted consequence is that {@code NarrationBudgetService} derives the monthly
 * text-to-speech spend by summing {@code scriptCharacterCount} over rows requested in the
 * current calendar month, so removing those rows resets that counter — a one-time
 * under-count of characters already billed this month, in exchange for a clean corpus.
 *
 * <p>Indexes are deliberately left alone: the collection is emptied, not dropped, so
 * {@code V021GeneraliseNarrationContentType}'s indexes remain and narration continues to
 * work without a further change unit.
 *
 * <p>Idempotent: a re-run finds no documents and no audio directory, deletes nothing and
 * logs zero counts. File removal is best effort inside {@link NarrationStorage#deleteAll}
 * and cannot throw, because an exception here would abort application startup.
 */
@ChangeUnit(id = "purge-narrations-for-voice-change", order = "030", author = "simonrowe")
public class V030PurgeNarrationsForVoiceChange {

  private static final Logger LOG =
      LoggerFactory.getLogger(V030PurgeNarrationsForVoiceChange.class);

  static final String COLLECTION = "narrations";

  @Execution
  public void execution(
      final MongoTemplate mongoTemplate,
      final NarrationStorage narrationStorage) {
    long documents = mongoTemplate.getCollection(COLLECTION)
        .deleteMany(new Document()).getDeletedCount();
    int audioFiles = narrationStorage.deleteAll();
    LOG.info("Purged {} narration documents and {} narration audio files after the "
        + "text-to-speech voice change", documents, audioFiles);
  }

  @RollbackExecution
  public void rollback() {
    // Nothing to roll back: the audio these documents pointed at has been deleted from
    // disk, so restoring the rows would only recreate records of files that are gone.
    // Narration is regenerated on demand, so the corpus rebuilds itself.
  }
}
