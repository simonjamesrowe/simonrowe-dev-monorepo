package com.simonrowe.platform;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One release, keyed by the full commit SHA.
 *
 * <p>A mutable class rather than a record, following {@code ArticleSummary}: the summary sweep
 * transitions the document in place through {@code PENDING} → {@code GENERATING} →
 * {@code READY}.
 *
 * <p>The {@code _id} is the SHA itself, which is what makes seeding idempotent — a second
 * insert of the same release is a duplicate-key error rather than a second row.
 */
@Document(collection = "platform_releases")
public class PlatformRelease {

  @Id
  private String id;

  private String shortSha;
  private Instant commitTime;
  private String subject;
  private String body;
  private String type;
  private List<String> filesChanged = List.of();
  private String summary;
  private ReleaseSummaryStatus summaryStatus = ReleaseSummaryStatus.PENDING;
  private int summaryAttempts;
  private Instant firstSeenAt;
  private ReleaseSource source = ReleaseSource.PUBLISHED_HISTORY;
  private Instant updatedAt;

  /**
   * Builds a pending release record from a baked commit.
   *
   * @param baked the commit as baked into the image
   * @param source how this record came to exist
   * @param now the seeding instant
   * @return a record awaiting its summary
   */
  public static PlatformRelease fromBaked(
      final BakedRelease baked, final ReleaseSource source, final Instant now) {
    PlatformRelease release = new PlatformRelease();
    release.id = baked.sha();
    release.shortSha = baked.shortSha();
    release.commitTime = baked.commitTime();
    release.subject = baked.subject();
    release.body = baked.body();
    release.type = baked.type();
    release.filesChanged = baked.filesChanged();
    release.summaryStatus = ReleaseSummaryStatus.PENDING;
    release.firstSeenAt = now;
    release.source = source;
    release.updatedAt = now;
    return release;
  }

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public String getShortSha() {
    return shortSha;
  }

  public void setShortSha(final String shortSha) {
    this.shortSha = shortSha;
  }

  public Instant getCommitTime() {
    return commitTime;
  }

  public void setCommitTime(final Instant commitTime) {
    this.commitTime = commitTime;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(final String subject) {
    this.subject = subject;
  }

  public String getBody() {
    return body;
  }

  public void setBody(final String body) {
    this.body = body;
  }

  public String getType() {
    return type;
  }

  public void setType(final String type) {
    this.type = type;
  }

  public List<String> getFilesChanged() {
    return filesChanged == null ? List.of() : List.copyOf(filesChanged);
  }

  public void setFilesChanged(final List<String> filesChanged) {
    this.filesChanged = filesChanged == null ? List.of() : List.copyOf(filesChanged);
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(final String summary) {
    this.summary = summary;
  }

  public ReleaseSummaryStatus getSummaryStatus() {
    return summaryStatus;
  }

  public void setSummaryStatus(final ReleaseSummaryStatus summaryStatus) {
    this.summaryStatus = summaryStatus;
  }

  public int getSummaryAttempts() {
    return summaryAttempts;
  }

  public void setSummaryAttempts(final int summaryAttempts) {
    this.summaryAttempts = summaryAttempts;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public void setFirstSeenAt(final Instant firstSeenAt) {
    this.firstSeenAt = firstSeenAt;
  }

  public ReleaseSource getSource() {
    return source;
  }

  public void setSource(final ReleaseSource source) {
    this.source = source;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
