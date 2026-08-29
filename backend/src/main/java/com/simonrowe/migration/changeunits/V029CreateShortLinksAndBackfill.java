package com.simonrowe.migration.changeunits;

import com.simonrowe.shortlink.ShortLink;
import com.simonrowe.shortlink.ShortLinkContentType;
import com.simonrowe.shortlink.ShortLinkSlugger;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Creates the {@code short_links} indexes and mints a link for every piece of content that
 * already exists.
 *
 * <p><b>Unlike {@code V020} and {@code V022}, this unit also writes data</b>, and that is
 * deliberate. Release records and narration indexes are derived, self-healing data that a
 * runtime process re-establishes after a restore, so they belong in a recorder rather than
 * a change unit. A slug is the opposite: a permanent public identifier, pasted into other
 * people's conversations, that no runtime process would ever recreate with the same value.
 * It has to be minted once, backed up, and restored.
 *
 * <p>The backfill is pure Mongo with no external I/O, so it is safe to let run against the
 * shared Testcontainers Mongo — unlike the LLM-calling change units this repository has
 * been bitten by before.
 *
 * <p>Re-running mints nothing: every insert is guarded by the unique
 * {@code (contentType, contentId)} index, and the pass skips content that already has a
 * link.
 */
@ChangeUnit(id = "create-short-links-and-backfill", order = "029", author = "simonrowe")
public class V029CreateShortLinksAndBackfill {

  static final String COLLECTION = "short_links";
  static final String CONTENT_INDEX = "idx_short_link_content";

  private static final Logger LOG =
      LoggerFactory.getLogger(V029CreateShortLinksAndBackfill.class);

  /** Bounded so a pathological title cannot spin the backfill forever. */
  private static final int MAX_NUMBERED_ATTEMPTS = 99;

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
    backfill(mongoTemplate);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).dropIndex(CONTENT_INDEX);
  }

  /**
   * Creates the indexes. Also called directly by {@code RestoreService}: a restore drops
   * collections and their indexes with them, and Mongock will not re-run a change unit it
   * has already recorded.
   *
   * <p>No index is declared on {@code _id} — it is the slug, and Mongo always indexes
   * {@code _id}, which is what the slug-collision detection relies on.
   *
   * <p>The compound index is <b>unique</b>, and that is what makes "exactly one link per
   * item" structural rather than aspirational: a concurrent re-save cannot mint a second
   * slug for content that already has one.
   *
   * @param mongoTemplate the template to create indexes through
   */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(CONTENT_INDEX)
        .on("contentType", Sort.Direction.ASC)
        .on("contentId", Sort.Direction.ASC)
        .unique());
  }

  private void backfill(final MongoTemplate mongoTemplate) {
    Set<String> takenSlugs = existingSlugs(mongoTemplate);

    int minted = 0;
    minted += backfillCollection(
        mongoTemplate, takenSlugs, "blogs", ShortLinkContentType.BLOG);
    minted += backfillCollection(
        mongoTemplate, takenSlugs, "aggregated_articles", ShortLinkContentType.ARTICLE);
    minted += backfillCollection(
        mongoTemplate, takenSlugs, "aggregated_events", ShortLinkContentType.EVENT);

    LOG.info("Short link backfill complete: {} minted, {} links in total",
        minted, takenSlugs.size());
  }

  private int backfillCollection(
      final MongoTemplate mongoTemplate,
      final Set<String> takenSlugs,
      final String collection,
      final ShortLinkContentType contentType
  ) {
    Set<String> alreadyLinked = linkedContentIds(mongoTemplate, contentType);

    int minted = 0;
    for (Document document : mongoTemplate.getCollection(collection).find()) {
      Object rawId = document.get("_id");
      if (rawId == null) {
        continue;
      }
      String contentId = rawId.toString();
      if (alreadyLinked.contains(contentId)) {
        continue;
      }

      String slug = freeSlug(takenSlugs, document.getString("title"));
      mongoTemplate.insert(
          ShortLink.minted(slug, contentType, contentId, Instant.now()), COLLECTION);
      takenSlugs.add(slug);
      minted++;
    }
    return minted;
  }

  /**
   * Picks a slug not already used in this run.
   *
   * <p>The backfill can hold every existing slug in memory — there are a few hundred — so
   * it resolves collisions against that set rather than by insert-and-catch. The runtime
   * path in {@code ShortLinkService} does the opposite, because there the set would be
   * stale the moment it was read.
   */
  private String freeSlug(final Set<String> takenSlugs, final String title) {
    String base = ShortLinkSlugger.slugify(title);
    if (!takenSlugs.contains(base)) {
      return base;
    }
    for (int attempt = 2; attempt <= MAX_NUMBERED_ATTEMPTS; attempt++) {
      String candidate = ShortLinkSlugger.withSuffix(base, attempt);
      if (!takenSlugs.contains(candidate)) {
        return candidate;
      }
    }
    String random = ShortLinkSlugger.randomCode();
    while (takenSlugs.contains(random)) {
      random = ShortLinkSlugger.randomCode();
    }
    return random;
  }

  private Set<String> existingSlugs(final MongoTemplate mongoTemplate) {
    Set<String> slugs = new HashSet<>();
    for (Document document
        : mongoTemplate.getCollection(COLLECTION).find().projection(new Document("_id", 1))) {
      Object id = document.get("_id");
      if (id != null) {
        slugs.add(id.toString());
      }
    }
    return slugs;
  }

  private Set<String> linkedContentIds(
      final MongoTemplate mongoTemplate,
      final ShortLinkContentType contentType
  ) {
    List<ShortLink> links = mongoTemplate.find(
        Query.query(Criteria.where("contentType").is(contentType.name())),
        ShortLink.class,
        COLLECTION);
    Set<String> ids = new HashSet<>(links.size());
    for (ShortLink link : links) {
      ids.add(link.contentId());
    }
    return ids;
  }
}
