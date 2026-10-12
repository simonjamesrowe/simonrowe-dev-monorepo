package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.playlist.PlaylistContentType;
import com.simonrowe.playlist.PlaylistItem;
import com.simonrowe.playlist.PlaylistItemStatus;
import com.simonrowe.playlist.PlaylistSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Drives the change unit directly against a real Mongo; Mongock is off in tests. Pure Mongo
 * I/O, so it is safe in the shared Testcontainers context.
 */
class V056CreatePlaylistTest extends AbstractIntegrationTest {

  private static final String SUMMARIES = "article_summaries";
  private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  @AfterEach
  void clean() {
    mongoTemplate.getCollection(V056CreatePlaylist.COLLECTION).drop();
    mongoTemplate.remove(new Query(), SUMMARIES);
  }

  @Test
  void heardItemsExpireAfterThirtyDaysThroughTtlIndex() {
    V056CreatePlaylist.createIndexes(mongoTemplate);

    Document index = mongoTemplate.getCollection(V056CreatePlaylist.COLLECTION)
        .listIndexes().into(new ArrayList<>()).stream()
        .filter(candidate -> V056CreatePlaylist.LISTENED_TTL_INDEX
            .equals(candidate.getString("name")))
        .findFirst()
        .orElseThrow();
    assertThat(index.get("key", Document.class).keySet()).containsExactly("listenedAt");
    assertThat(((Number) index.get("expireAfterSeconds")).longValue())
        .isEqualTo(Duration.ofDays(30).toSeconds());
  }

  @Test
  void queuesRecentReadySummariesDatedWhenTheyWereWritten() {
    summary("a-new", "READY", NOW.minus(Duration.ofHours(2)));
    summary("a-old", "READY", NOW.minus(Duration.ofDays(13)));
    summary("a-too-old", "READY", NOW.minus(Duration.ofDays(15)));
    summary("a-failed", "FAILED", NOW.minus(Duration.ofHours(1)));
    summary("a-generating", "GENERATING", null);

    int added = V056CreatePlaylist.backfill(mongoTemplate, NOW);

    assertThat(added).isEqualTo(2);
    List<PlaylistItem> items = mongoTemplate.find(
        new Query().with(Sort.by("addedAt")), PlaylistItem.class);
    assertThat(items).extracting(PlaylistItem::contentId).containsExactly("a-old", "a-new");
    assertThat(items).allSatisfy(item -> {
      assertThat(item.contentType()).isEqualTo(PlaylistContentType.ARTICLE_SUMMARY);
      assertThat(item.status()).isEqualTo(PlaylistItemStatus.QUEUED);
      assertThat(item.addedVia()).isEqualTo(PlaylistSource.BACKFILLED);
      assertThat(item.listenedAt()).isNull();
    });
    assertThat(items.get(0).id()).isEqualTo("ARTICLE_SUMMARY:a-old");
    assertThat(items.get(0).addedAt()).isEqualTo(NOW.minus(Duration.ofDays(13)));
  }

  @Test
  void reRunAddsNothingAndLeavesWhatSomeoneDidAlone() {
    summary("a-1", "READY", NOW.minus(Duration.ofHours(2)));
    V056CreatePlaylist.backfill(mongoTemplate, NOW);
    mongoTemplate.getCollection(V056CreatePlaylist.COLLECTION).updateOne(
        new Document("_id", "ARTICLE_SUMMARY:a-1"),
        new Document("$set", new Document("status", "LISTENED")));

    int added = V056CreatePlaylist.backfill(mongoTemplate, NOW);

    assertThat(added).isZero();
    PlaylistItem item = mongoTemplate.findById("ARTICLE_SUMMARY:a-1", PlaylistItem.class);
    assertThat(item.status()).isEqualTo(PlaylistItemStatus.LISTENED);
  }

  private void summary(final String articleId, final String status, final Instant completedAt) {
    Document document = new Document("_id", "summary-" + articleId)
        .append("articleId", articleId)
        .append("status", status);
    if (completedAt != null) {
      document.append("completedAt", Date.from(completedAt));
    }
    mongoTemplate.insert(document, SUMMARIES);
  }
}
