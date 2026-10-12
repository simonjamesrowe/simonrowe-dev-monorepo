package com.simonrowe.migration.changeunits;

import com.simonrowe.playlist.PlaylistContentType;
import com.simonrowe.playlist.PlaylistItem;
import com.simonrowe.playlist.PlaylistItemStatus;
import com.simonrowe.playlist.PlaylistSettings;
import com.simonrowe.playlist.PlaylistSource;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Creates the shared playlist and fills it with the last two weeks of summaries.
 *
 * <p>The index is a TTL on {@code listenedAt}: an item played to the end is removed 30 days
 * later. Queued items have no {@code listenedAt}, and Mongo's TTL monitor skips documents
 * without the field, so nothing waiting to be heard can expire.
 *
 * <p>The backfill is what makes the playlist useful on the day it ships: the summaries made
 * before it existed are exactly the backlog it is for. It queues every {@code READY} summary
 * completed in the last {@link #BACKFILL_WINDOW} through a {@code $setOnInsert} upsert, so a
 * re-run adds nothing and never touches an item someone has since pinned or heard. Pure Mongo,
 * no external I/O, so it is safe in the shared Testcontainers Mongo.
 *
 * <p>{@code RestoreService} calls {@link #createIndexes} directly, because a restore drops the
 * collection and Mongock never re-runs a recorded unit.
 */
@ChangeUnit(id = "create-playlist", order = "056", author = "simonrowe")
public class V056CreatePlaylist {

  public static final String COLLECTION = PlaylistItem.COLLECTION;
  public static final String LISTENED_TTL_INDEX = "idx_playlist_listened_ttl";
  static final Duration LISTENED_RETENTION = Duration.ofDays(30);
  static final Duration BACKFILL_WINDOW = Duration.ofDays(14);

  private static final Logger LOG = LoggerFactory.getLogger(V056CreatePlaylist.class);

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
    int added = backfill(mongoTemplate, Instant.now());
    LOG.info("Created the playlist: {} recent summaries queued", added);
  }

  /** Creates both collections when missing, and the TTL index. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    if (!mongoTemplate.collectionExists(COLLECTION)) {
      mongoTemplate.createCollection(COLLECTION);
    }
    if (!mongoTemplate.collectionExists(PlaylistSettings.COLLECTION)) {
      mongoTemplate.createCollection(PlaylistSettings.COLLECTION);
    }
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(LISTENED_TTL_INDEX)
        .on("listenedAt", Sort.Direction.ASC)
        .expire(LISTENED_RETENTION.toSeconds(), TimeUnit.SECONDS));
  }

  static int backfill(final MongoTemplate mongoTemplate, final Instant now) {
    Query recentReady = Query.query(Criteria.where("status").is("READY")
            .and("completedAt").gte(Date.from(now.minus(BACKFILL_WINDOW))))
        .with(Sort.by(Sort.Direction.ASC, "completedAt"));
    int added = 0;
    for (Document summary : mongoTemplate.find(recentReady, Document.class, "article_summaries")) {
      String articleId = summary.getString("articleId");
      Date completedAt = summary.getDate("completedAt");
      if (articleId == null || completedAt == null) {
        continue;
      }
      String id = PlaylistItem.idFor(PlaylistContentType.ARTICLE_SUMMARY, articleId);
      Update update = new Update()
          .setOnInsert("contentType", PlaylistContentType.ARTICLE_SUMMARY.name())
          .setOnInsert("contentId", articleId)
          .setOnInsert("status", PlaylistItemStatus.QUEUED.name())
          .setOnInsert("progressSeconds", 0.0)
          .setOnInsert("addedVia", PlaylistSource.BACKFILLED.name())
          .setOnInsert("voiceRequested", false)
          .setOnInsert("addedAt", completedAt)
          .setOnInsert("updatedAt", Date.from(now));
      if (mongoTemplate.upsert(Query.query(Criteria.where("_id").is(id)), update, COLLECTION)
          .getUpsertedId() != null) {
        added++;
      }
    }
    return added;
  }

  /**
   * Drops the TTL index and the backfilled items nobody has used: still queued, never started
   * and never asked to be voiced. Anything someone has listened to or chosen stays.
   */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).dropIndex(LISTENED_TTL_INDEX);
    mongoTemplate.remove(Query.query(Criteria.where("addedVia")
            .is(PlaylistSource.BACKFILLED.name())
            .and("status").is(PlaylistItemStatus.QUEUED.name())
            .and("progressSeconds").is(0.0)
            .and("voiceRequested").is(false)),
        COLLECTION);
  }
}
