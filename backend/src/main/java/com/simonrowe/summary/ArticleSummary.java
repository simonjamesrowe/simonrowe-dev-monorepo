package com.simonrowe.summary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One in-depth, globally shared summary of one aggregated news article.
 *
 * <p>A mutable class rather than a record because the generation flow transitions it in
 * place, exactly as {@code Narration} does.
 *
 * <p><b>Why the id is article-keyed and not content-addressed.</b> {@code Narration}
 * fingerprints the script text, which is correct for blogs: editing a post changes the
 * hash, and the stale audio is marked {@code STALE} automatically. Aggregated articles
 * behave differently. They are immutable snapshots of third-party content, but their
 * <em>source text</em> comes from a fresh re-scrape that varies between runs.
 * Content-addressing would therefore produce spurious cache misses and re-spend on every
 * scrape drift. Keying on {@code articleId} plus a summary format version yields one stable
 * summary per article, and bumping that version cleanly invalidates every summary when the
 * prompt changes.
 *
 * <p>Indexes are created by a Mongock change unit — {@code auto-index-creation} is off in
 * this project, so the annotations below are documentation rather than mechanism.
 */
@Document(collection = "article_summaries")
@CompoundIndexes({
    @CompoundIndex(
        name = "idx_article_summary_status_article",
        def = "{'status': 1, 'articleId': 1}")
})
public class ArticleSummary {

  @Id
  private String id;
  @Indexed
  private String articleId;
  private SummaryStatus status;
  private long version;
  private String body;
  private String model;
  private int sourceCharacterCount;
  private Instant requestedAt;
  private Instant completedAt;
  private Instant updatedAt;
  private String failureCode;
  private boolean retryable;

  @SuppressWarnings("unused")
  private ArticleSummary() {
  }

  public ArticleSummary(
      final String id,
      final String articleId,
      final Instant now
  ) {
    this.id = id;
    this.articleId = articleId;
    this.status = SummaryStatus.GENERATING;
    this.version = 1;
    this.requestedAt = now;
    this.updatedAt = now;
    this.retryable = false;
  }

  /**
   * The deterministic document id for an article under a given prompt version. This is the
   * dedup key: the insert-first guard relies on Mongo's own {@code _id} uniqueness, so no
   * additional unique index is needed.
   *
   * @param formatVersion the summary format version, bumped whenever the prompt changes
   * @param articleId the aggregated article id
   * @return the hex-encoded SHA-256 id
   */
  public static String idFor(final String formatVersion, final String articleId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(
          (formatVersion + articleId).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  public String id() {
    return id;
  }

  public String articleId() {
    return articleId;
  }

  public SummaryStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  public String body() {
    return body;
  }

  public String model() {
    return model;
  }

  public int sourceCharacterCount() {
    return sourceCharacterCount;
  }

  public Instant requestedAt() {
    return requestedAt;
  }

  public Instant completedAt() {
    return completedAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public String failureCode() {
    return failureCode;
  }

  public boolean retryable() {
    return retryable;
  }

  public void markReady(
      final String prose,
      final String modelName,
      final int characterCount,
      final Instant now
  ) {
    status = SummaryStatus.READY;
    body = prose;
    model = modelName;
    sourceCharacterCount = characterCount;
    completedAt = now;
    failureCode = null;
    retryable = false;
    touch(now);
  }

  public void markFailed(final String code, final boolean canRetry, final Instant now) {
    status = SummaryStatus.FAILED;
    failureCode = code;
    retryable = canRetry;
    body = null;
    completedAt = null;
    touch(now);
  }

  /** Puts a failed summary back into generation for a retry. */
  public void markGenerating(final Instant now) {
    status = SummaryStatus.GENERATING;
    failureCode = null;
    retryable = false;
    touch(now);
  }

  /**
   * Bumps the version alongside {@code updatedAt}. The version is what a long-polling
   * client waits on; {@code updatedAt} is what the stale-reclaim guard reads. They must
   * move together, so no caller sets either directly.
   */
  private void touch(final Instant now) {
    version++;
    updatedAt = now;
  }
}
