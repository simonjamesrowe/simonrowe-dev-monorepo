package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.shortlink.ShortLink;
import com.simonrowe.shortlink.ShortLinkContentType;
import com.simonrowe.shortlink.ShortLinkSlugger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Exercises the backfill against a real MongoDB. Mongock is disabled in tests, so the
 * change unit is driven directly.
 *
 * <p>The backfill does live I/O against Mongo only — no LLM call, no HTTP — which is what
 * makes it safe to run in the shared Testcontainers context at all.
 */
class V029CreateShortLinksAndBackfillTest extends AbstractIntegrationTest {

  private static final String SHORT_LINKS = "short_links";
  private static final String BLOGS = "blogs";
  private static final String ARTICLES = "aggregated_articles";
  private static final String EVENTS = "aggregated_events";

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V029CreateShortLinksAndBackfill changeUnit =
      new V029CreateShortLinksAndBackfill();

  @BeforeEach
  @AfterEach
  void dropCollections() {
    mongoTemplate.getCollection(SHORT_LINKS).drop();
    mongoTemplate.getCollection(BLOGS).drop();
    mongoTemplate.getCollection(ARTICLES).drop();
    mongoTemplate.getCollection(EVENTS).drop();
  }

  @Test
  void createsTheContentIndexAsUnique() {
    // The uniqueness is the point: without it a concurrent re-save can mint a second
    // slug for content that already has one, and both links then exist in the wild.
    changeUnit.execution(mongoTemplate);

    Document index = indexNamed("idx_short_link_content");
    assertThat(index).isNotNull();
    assertThat(index.getBoolean("unique")).isTrue();
    assertThat(index.get("key", Document.class).keySet())
        .containsExactly("contentType", "contentId");
  }

  @Test
  void mintsOneLinkForEveryExistingItemAcrossAllThreeCollections() {
    seed(BLOGS, "blog-1", "Exactly-once semantics in Kafka");
    seed(ARTICLES, "article-1", "Spring AI goes GA");
    seed(EVENTS, "event-1", "Devoxx UK 2026");

    changeUnit.execution(mongoTemplate);

    assertThat(links()).hasSize(3);
    assertThat(slugByContentId())
        .containsEntry("blog-1", "exactly-once")
        .containsEntry("article-1", "spring-ai-goes-ga")
        .containsEntry("event-1", "devoxx-uk-2026");
    assertThat(links()).extracting(ShortLink::contentType)
        .containsExactlyInAnyOrder(
            ShortLinkContentType.BLOG,
            ShortLinkContentType.ARTICLE,
            ShortLinkContentType.EVENT);
  }

  @Test
  void mintsNothingNewOnSecondRuns() {
    seed(BLOGS, "blog-1", "Kafka tuning");
    seed(ARTICLES, "article-1", "Spring AI goes GA");
    changeUnit.execution(mongoTemplate);
    Map<String, String> afterFirst = slugByContentId();

    changeUnit.execution(mongoTemplate);

    assertThat(slugByContentId()).isEqualTo(afterFirst);
    assertThat(links()).hasSize(2);
  }

  @Test
  void mintsOnlyForContentAddedSinceTheLastRun() {
    seed(BLOGS, "blog-1", "Kafka tuning");
    changeUnit.execution(mongoTemplate);
    final String originalSlug = slugByContentId().get("blog-1");

    seed(BLOGS, "blog-2", "Spring AI goes GA");
    changeUnit.execution(mongoTemplate);

    assertThat(links()).hasSize(2);
    // The pre-existing link is untouched — a slug already pasted somewhere must survive
    // every later run.
    assertThat(slugByContentId().get("blog-1")).isEqualTo(originalSlug);
  }

  @Test
  void givesTwoItemsWithTheSameTitleDistinctSlugs() {
    seed(BLOGS, "blog-1", "Kafka tuning");
    seed(BLOGS, "blog-2", "Kafka tuning");
    seed(ARTICLES, "article-1", "Kafka tuning");

    changeUnit.execution(mongoTemplate);

    assertThat(slugByContentId().values()).doesNotHaveDuplicates();
    assertThat(links()).extracting(ShortLink::slug).allSatisfy(slug ->
        assertThat(slug).hasSizeLessThanOrEqualTo(ShortLinkSlugger.MAX_LENGTH));
  }

  @Test
  void doesNotReuseSlugsAlreadyHeldByContentMintedAtRuntime() {
    // The runtime mint path and the backfill both write to the same slug namespace, so
    // the backfill has to see what is already there rather than assuming an empty table.
    mongoTemplate.getCollection(SHORT_LINKS).insertOne(new Document(Map.of(
        "_id", "kafka-tuning",
        "contentType", "ARTICLE",
        "contentId", "article-1",
        "clickCount", 0L)));
    seed(BLOGS, "blog-1", "Kafka tuning");

    changeUnit.execution(mongoTemplate);

    assertThat(slugByContentId().get("blog-1")).isEqualTo("kafka-tuning-2");
  }

  @Test
  void survivesAnItemWithNoTitleAtAll() {
    mongoTemplate.getCollection(BLOGS)
        .insertOne(new Document("_id", "blog-untitled").append("published", true));

    changeUnit.execution(mongoTemplate);

    assertThat(links()).hasSize(1);
    assertThat(links().get(0).slug()).matches("^[a-z0-9]{6}$");
  }

  @Test
  void mintsNothingWhenThereIsNoContent() {
    changeUnit.execution(mongoTemplate);

    assertThat(links()).isEmpty();
  }

  private void seed(final String collection, final String id, final String title) {
    mongoTemplate.getCollection(collection)
        .insertOne(new Document("_id", id).append("title", title));
  }

  private List<ShortLink> links() {
    return mongoTemplate.findAll(ShortLink.class, SHORT_LINKS);
  }

  private Map<String, String> slugByContentId() {
    return links().stream()
        .collect(Collectors.toMap(
            ShortLink::contentId, ShortLink::slug, (a, b) -> a));
  }

  private Document indexNamed(final String name) {
    for (Document index : mongoTemplate.getCollection(SHORT_LINKS).listIndexes()) {
      if (name.equals(index.getString("name"))) {
        return index;
      }
    }
    return null;
  }
}
