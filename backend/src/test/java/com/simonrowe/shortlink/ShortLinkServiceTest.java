package com.simonrowe.shortlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V029CreateShortLinksAndBackfill;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Exercises minting against a real MongoDB, because the behaviour under test <em>is</em>
 * the unique index: the idempotency guarantee is enforced by Mongo, not by application
 * code, so a mocked repository would assert nothing worth knowing.
 *
 * <p>Mongock is disabled in tests, so the indexes are created directly — the same route
 * {@code RestoreService} takes.
 */
class ShortLinkServiceTest extends AbstractIntegrationTest {

  private static final String COLLECTION = "short_links";

  @Autowired
  private ShortLinkService service;

  @Autowired
  private ShortLinkRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  @AfterEach
  void resetCollection() {
    mongoTemplate.getCollection(COLLECTION).drop();
    V029CreateShortLinksAndBackfill.createIndexes(mongoTemplate);
  }

  @Test
  void mintsReadableSlugsFromTheTitle() {
    String slug = service.ensureFor(
        ShortLinkContentType.BLOG, "blog-1", "Exactly-once semantics in Kafka");

    assertThat(slug).isEqualTo("exactly-once");
  }

  @Test
  void isIdempotentSoResavingNeverInvalidatesLinksAlreadyShared() {
    String first = service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Kafka tuning");
    String second = service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Kafka tuning");

    assertThat(second).isEqualTo(first);
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void keepsTheOriginalSlugEvenWhenTheTitleChanges() {
    // A link already pasted into someone else's Slack must not stop working because the
    // post was retitled.
    String original = service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Kafka tuning");

    String afterRename =
        service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Something else entirely");

    assertThat(afterRename).isEqualTo(original);
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void givesTwoDistinctItemsWithTheSameTitleDistinctSlugs() {
    String first = service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Kafka tuning");
    String second = service.ensureFor(ShortLinkContentType.BLOG, "blog-2", "Kafka tuning");

    assertThat(second).isNotEqualTo(first);
    assertThat(first).isEqualTo("kafka-tuning");
    assertThat(second).isEqualTo("kafka-tuning-2");
    assertThat(second.length()).isLessThanOrEqualTo(ShortLinkSlugger.MAX_LENGTH);
  }

  @Test
  void resolvesCollisionsAcrossContentTypesToo() {
    // The slug namespace is global — it is the _id — so a blog and an article with the
    // same title still get different addresses.
    String blog = service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Spring AI");
    String article = service.ensureFor(ShortLinkContentType.ARTICLE, "article-1", "Spring AI");

    assertThat(article).isNotEqualTo(blog);
  }

  @Test
  void keepsTheCeilingThroughLongRunsOfCollisions() {
    List<String> slugs = IntStream.rangeClosed(1, 12)
        .mapToObj(i -> service.ensureFor(
            ShortLinkContentType.BLOG, "blog-" + i, "An extraordinarily long headline"))
        .toList();

    assertThat(slugs).doesNotHaveDuplicates();
    assertThat(slugs).allSatisfy(slug ->
        assertThat(slug).hasSizeLessThanOrEqualTo(ShortLinkSlugger.MAX_LENGTH));
  }

  @Test
  void urlsForResolvesWholeListingsInOneBatch() {
    service.ensureFor(ShortLinkContentType.ARTICLE, "a1", "First article");
    service.ensureFor(ShortLinkContentType.ARTICLE, "a2", "Second article");

    Map<String, String> urls =
        service.urlsFor(ShortLinkContentType.ARTICLE, List.of("a1", "a2", "a3"));

    assertThat(urls).hasSize(2);
    assertThat(urls.get("a1")).isEqualTo("https://simonrowe.dev/s/first-article");
    assertThat(urls.get("a2")).isEqualTo("https://simonrowe.dev/s/second-article");
    // An id with no link is absent rather than mapped to null, so the caller's
    // Map::get yields null and the Share control is simply hidden.
    assertThat(urls).doesNotContainKey("a3");
  }

  @Test
  void urlsForIsAbsoluteSoTheFrontendNeverConcatenatesBases() {
    service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Kafka tuning");

    assertThat(service.urlsFor(ShortLinkContentType.BLOG, List.of("blog-1")).get("blog-1"))
        .startsWith("https://")
        .isEqualTo("https://simonrowe.dev/s/kafka-tuning");
  }

  @Test
  void urlsForShortCircuitsOnAnEmptyListing() {
    assertThat(service.urlsFor(ShortLinkContentType.BLOG, List.of())).isEmpty();
    assertThat(service.urlsFor(ShortLinkContentType.BLOG, null)).isEmpty();
  }

  @Test
  void urlForReturnsEmptyForContentWithNoLink() {
    assertThat(service.urlFor(ShortLinkContentType.BLOG, "never-minted")).isEmpty();
  }

  @Test
  void recordClickIncrementsTheCountAndStampsTheTime() {
    String slug = service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Kafka tuning");

    service.recordClick(slug);
    service.recordClick(slug);

    Optional<ShortLink> link = repository.findById(slug);
    assertThat(link).isPresent();
    assertThat(link.get().clickCount()).isEqualTo(2L);
    assertThat(link.get().lastClickedAt()).isNotNull();
  }

  @Test
  void recordClickOnAnUnknownSlugIsSilentlyHarmless() {
    // The redirect calls this before it knows whether the write will land; an unknown
    // slug never reaches it, but a deleted one could.
    service.recordClick("no-such-slug");

    assertThat(repository.count()).isZero();
  }

  @Test
  void clickCountsForResolvesListingsInOneBatch() {
    String slug = service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Kafka tuning");
    service.ensureFor(ShortLinkContentType.BLOG, "blog-2", "Spring AI");
    service.recordClick(slug);

    Map<String, Long> counts =
        service.clickCountsFor(ShortLinkContentType.BLOG, List.of("blog-1", "blog-2", "blog-3"));

    assertThat(counts).containsOnly(
        entry("blog-1", 1L),
        entry("blog-2", 0L));
  }

  @Test
  void resolveFindsLinksBySlug() {
    String slug = service.ensureFor(ShortLinkContentType.ARTICLE, "a1", "Spring AI");

    assertThat(service.resolve(slug))
        .get()
        .satisfies(link -> {
          assertThat(link.contentType()).isEqualTo(ShortLinkContentType.ARTICLE);
          assertThat(link.contentId()).isEqualTo("a1");
        });
    assertThat(service.resolve("no-such-slug")).isEmpty();
  }

  @Test
  void theUniqueIndexIsWhatStopsSecondLinksForTheSameContent() {
    // Not a redundant check on ensureFor: it asserts the index exists, which is the only
    // thing standing between a concurrent double-save and two slugs for one post.
    service.ensureFor(ShortLinkContentType.BLOG, "blog-1", "Kafka tuning");

    assertThat(uniqueContentIndexExists()).isTrue();
  }

  private boolean uniqueContentIndexExists() {
    for (Document index : mongoTemplate.getCollection(COLLECTION).listIndexes()) {
      if ("idx_short_link_content".equals(index.getString("name"))) {
        return Boolean.TRUE.equals(index.getBoolean("unique"));
      }
    }
    return false;
  }
}
