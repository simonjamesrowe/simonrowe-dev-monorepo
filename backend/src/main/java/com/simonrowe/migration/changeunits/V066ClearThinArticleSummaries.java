package com.simonrowe.migration.changeunits;

import com.simonrowe.narration.NarrationContentType;
import com.simonrowe.narration.NarrationService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Deletes the article summaries that were written without the article, now that
 * {@code ReaderFallbackClient} can read pages that refuse the scrapers.
 *
 * <p>Two kinds of row go:
 * <ul>
 *   <li>{@code FAILED} with {@code INSUFFICIENT_SOURCE_TEXT}. That failure is non-retryable, so
 *       without this the drawer would keep saying "not enough of this article" for ever, even
 *       though the article can now be read. "Scaling Git at OpenAI" is the one that was
 *       reported.</li>
 *   <li>{@code READY} with a {@code sourceCharacterCount} under 500, the floor
 *       {@code ArticleSourceTextProvider} uses for "plausibly an interstitial, not the body".
 *       These were written from a feed blurb or a consent wall. The OpenAI Habitat summary is
 *       one: written from two sentences, it says the source "does not provide further
 *       technical detail". Rows with no {@code sourceCharacterCount} at all are left alone,
 *       because {@code $lt} does not match a missing field.</li>
 * </ul>
 *
 * <p>Safe to delete: a summary is derived data. The next Summarise click regenerates it from
 * the article, through the same cascade, so nothing is lost but one model call each. Every
 * other row survives, including summaries written from 500 characters or more, failures with
 * any other code (which are retried on request anyway) and generations in progress.
 *
 * <p>The narrations of each deleted READY summary are invalidated through
 * {@link NarrationService#invalidate}, which marks them stale and deletes their audio, as an
 * edited summary would. Without it the Listen buttons, which play the newest READY narration
 * per article, would keep playing the old summary's audio beside the new summary's text.
 *
 * <p>500 is written here rather than read from {@code ArticleSourceTextProvider}: a change
 * unit runs once, and must keep meaning what it meant when it ran.
 *
 * <p>Idempotent: a second run matches nothing. The rollback does nothing, because the rows
 * regenerate on request.
 */
@ChangeUnit(id = "clear-thin-article-summaries", order = "066", author = "simonrowe")
public class V066ClearThinArticleSummaries {

  private static final Logger log = LoggerFactory.getLogger(V066ClearThinArticleSummaries.class);

  static final String COLLECTION = "article_summaries";

  /** {@code ArticleSourceTextProvider.MIN_USABLE_SOURCE_CHARS} when this unit was written. */
  static final int USABLE_SOURCE_CHARS = 500;

  static final Document THIN = new Document("$or", List.of(
      new Document("status", "FAILED").append("failureCode", "INSUFFICIENT_SOURCE_TEXT"),
      new Document("status", "READY")
          .append("sourceCharacterCount", new Document("$lt", USABLE_SOURCE_CHARS))));

  @Execution
  public void execution(
      final MongoTemplate mongoTemplate,
      final NarrationService narrationService) {
    List<Object> ids = new ArrayList<>();
    List<String> narratedArticleIds = new ArrayList<>();
    for (Document summary : mongoTemplate.getCollection(COLLECTION).find(THIN)) {
      ids.add(summary.get("_id"));
      if ("READY".equals(summary.getString("status"))
          && summary.getString("articleId") != null) {
        narratedArticleIds.add(summary.getString("articleId"));
      }
    }
    if (ids.isEmpty()) {
      log.info("No thin article summaries to clear");
      return;
    }
    long deleted = mongoTemplate.getCollection(COLLECTION)
        .deleteMany(new Document("_id", new Document("$in", ids)))
        .getDeletedCount();
    // After the delete, so the summary is gone and every narration of it reads as stale.
    narratedArticleIds.forEach(articleId ->
        narrationService.invalidate(NarrationContentType.ARTICLE_SUMMARY, articleId));
    log.info("""
        Cleared {} thin article summaries ({} written from under {} characters), \
        so they regenerate from the full article""",
        deleted, narratedArticleIds.size(), USABLE_SOURCE_CHARS);
  }

  @RollbackExecution
  public void rollback() {
    // Nothing to put back: summaries regenerate on request, and the audio of the deleted ones
    // has been removed, so restoring the rows would point at summaries nothing narrates.
  }
}
