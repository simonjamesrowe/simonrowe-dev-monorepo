package com.simonrowe.shortlink;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Mints, resolves and counts share links.
 *
 * <p>Minting is idempotent: an existing link always wins and is returned unchanged.
 * Because the same item always gets back its own slug, collisions only ever arise between
 * <em>different</em> items with similar titles.
 */
@Service
public class ShortLinkService {

  private static final Logger LOG = LoggerFactory.getLogger(ShortLinkService.class);

  /**
   * Numbered suffixes run {@code -2} … {@code -99} before falling back to a random code.
   * Reaching 99 distinct items whose titles all truncate to the same 17-odd characters is
   * not a realistic content problem; the fallback exists so the loop is bounded, not
   * because anyone expects to use it.
   */
  private static final int MAX_NUMBERED_ATTEMPTS = 99;

  /** Bounded so a pathological run of random collisions cannot spin forever. */
  private static final int MAX_RANDOM_ATTEMPTS = 5;

  private final ShortLinkRepository repository;
  private final MongoTemplate mongoTemplate;
  private final ShortLinkProperties properties;

  public ShortLinkService(
      final ShortLinkRepository repository,
      final MongoTemplate mongoTemplate,
      final ShortLinkProperties properties
  ) {
    this.repository = repository;
    this.mongoTemplate = mongoTemplate;
    this.properties = properties;
  }

  /**
   * Returns the share slug for one piece of content, minting it if it has none.
   *
   * <p>Idempotent by contract: calling this twice for the same content returns the same
   * slug and leaves one document. That is what lets the blog update path call it on every
   * save without ever invalidating a link someone has already pasted somewhere.
   *
   * <p><b>Collision detection is an insert that fails, never a read that says "free".</b>
   * A pre-read is a race; the unique {@code _id} is the truth. This is the same
   * insert-first dedup guard {@code ArticleSummaryService} uses.
   *
   * <p>A duplicate-key error has two possible causes and they need opposite responses: the
   * slug was taken by other content (retry with a suffix), or another thread minted a link
   * for <em>this</em> content a moment ago (return theirs). They are told apart by
   * re-reading the content index, not by parsing the error message.
   *
   * @param contentType which collection the content lives in
   * @param contentId the content's id
   * @param title the content's title, used to derive a readable slug
   * @return the slug, existing or newly minted
   */
  public String ensureFor(
      final ShortLinkContentType contentType,
      final String contentId,
      final String title
  ) {
    Optional<ShortLink> existing = repository.findByContentTypeAndContentId(contentType, contentId);
    if (existing.isPresent()) {
      return existing.get().slug();
    }

    String base = ShortLinkSlugger.slugify(title);
    for (int attempt = 1; attempt <= MAX_NUMBERED_ATTEMPTS; attempt++) {
      String candidate = attempt == 1 ? base : ShortLinkSlugger.withSuffix(base, attempt);
      Optional<String> minted = tryInsert(candidate, contentType, contentId);
      if (minted.isPresent()) {
        return minted.get();
      }
    }

    for (int attempt = 0; attempt < MAX_RANDOM_ATTEMPTS; attempt++) {
      Optional<String> minted =
          tryInsert(ShortLinkSlugger.randomCode(), contentType, contentId);
      if (minted.isPresent()) {
        return minted.get();
      }
    }

    throw new IllegalStateException(
        "Could not mint a short link for " + contentType + " " + contentId);
  }

  /**
   * One insert attempt.
   *
   * @return the slug when the insert succeeded, or the slug another thread minted for this
   *     same content; empty when the slug itself was taken and the caller should retry
   */
  private Optional<String> tryInsert(
      final String candidate,
      final ShortLinkContentType contentType,
      final String contentId
  ) {
    try {
      ShortLink inserted = repository.insert(
          ShortLink.minted(candidate, contentType, contentId, Instant.now()));
      return Optional.of(inserted.slug());
    } catch (DuplicateKeyException e) {
      // Either the slug is taken, or the unique (contentType, contentId) index fired
      // because a concurrent call already minted for this content. Only a read tells them
      // apart, and only in the second case is there nothing left to do.
      return repository.findByContentTypeAndContentId(contentType, contentId)
          .map(ShortLink::slug);
    }
  }

  /**
   * The absolute share URLs for a whole listing, in one query.
   *
   * <p>Batched deliberately: rendering 24 news cards costs one extra query, not 24. The
   * lookup is served by the unique {@code (contentType, contentId)} index.
   *
   * @param contentType which collection the content lives in
   * @param contentIds the ids to resolve
   * @return content id to absolute share URL; ids with no link are simply absent
   */
  public Map<String, String> urlsFor(
      final ShortLinkContentType contentType,
      final Collection<String> contentIds
  ) {
    if (contentIds == null || contentIds.isEmpty()) {
      return Map.of();
    }
    List<ShortLink> links = repository.findByContentTypeAndContentIdIn(contentType, contentIds);
    Map<String, String> urls = new HashMap<>(links.size());
    for (ShortLink link : links) {
      urls.put(link.contentId(), urlOf(link.slug()));
    }
    return urls;
  }

  /**
   * The absolute share URL for one item.
   *
   * @param contentType which collection the content lives in
   * @param contentId the content's id
   * @return the URL, or empty when the content has no link yet
   */
  public Optional<String> urlFor(
      final ShortLinkContentType contentType,
      final String contentId
  ) {
    return repository.findByContentTypeAndContentId(contentType, contentId)
        .map(link -> urlOf(link.slug()));
  }

  /**
   * The click counts for a listing, in one query.
   *
   * @param contentType which collection the content lives in
   * @param contentIds the ids to resolve
   * @return content id to click count; ids with no link are simply absent
   */
  public Map<String, Long> clickCountsFor(
      final ShortLinkContentType contentType,
      final Collection<String> contentIds
  ) {
    if (contentIds == null || contentIds.isEmpty()) {
      return Map.of();
    }
    List<ShortLink> links = repository.findByContentTypeAndContentIdIn(contentType, contentIds);
    Map<String, Long> counts = new HashMap<>(links.size());
    for (ShortLink link : links) {
      counts.put(link.contentId(), link.clickCount());
    }
    return counts;
  }

  /**
   * Resolves a slug to its link.
   *
   * @param slug the share address
   * @return the link, or empty for an unknown slug
   */
  public Optional<ShortLink> resolve(final String slug) {
    return repository.findById(slug);
  }

  /**
   * Builds the absolute share URL for a slug.
   *
   * <p>Absolute so the frontend never concatenates a base — the field it receives is ready
   * to hand to a share sheet or a clipboard.
   *
   * @param slug the share address
   * @return the full URL
   */
  public String urlOf(final String slug) {
    return properties.absolute("/s/" + slug);
  }

  /**
   * Records one human open.
   *
   * <p>Fire and forget. A datastore failure must never stop the redirect: the counter is
   * the least important thing the share endpoint does, and a visitor who cannot reach the
   * content because a statistic could not be written has been failed for no reason.
   *
   * <p>The failure is logged rather than swallowed silently — an increment that vanishes
   * with no trace makes an under-counting bug undiagnosable.
   *
   * @param slug the share address that was opened
   */
  public void recordClick(final String slug) {
    try {
      mongoTemplate.updateFirst(
          Query.query(Criteria.where("_id").is(slug)),
          new Update().inc("clickCount", 1L).set("lastClickedAt", Instant.now()),
          ShortLink.class);
    } catch (RuntimeException e) {
      LOG.warn("Could not record a click for short link '{}': {}", slug, e.toString());
    }
  }
}
