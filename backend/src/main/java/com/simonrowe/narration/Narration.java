package com.simonrowe.narration;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One generated audio narration.
 *
 * <p>Identified by {@link #fingerprint}, which is content-addressed over the script text
 * and voice settings and is also the {@code _id} and the directory the MP3 is stored in.
 * That is what makes staleness free: change the content, get a different fingerprint, and
 * the old narration is marked {@code STALE}.
 *
 * <p>Generalised from a single blog reference to {@code contentType} + {@code contentId} so
 * the one pipeline serves blogs and article summaries alike. Migrated by
 * {@code V021GeneraliseNarrationContentType}.
 */
@Document(collection = "narrations")
@CompoundIndexes({
    @CompoundIndex(
        name = "idx_narration_content_updated",
        def = "{'contentType': 1, 'contentId': 1, 'updatedAt': -1}"),
    @CompoundIndex(name = "idx_narration_status_lease", def = "{'status': 1, 'leaseUntil': 1}")
})
public class Narration {

  @Id
  private String id;
  private NarrationContentType contentType;
  @Indexed
  private String contentId;
  @Indexed(unique = true)
  private String fingerprint;
  private NarrationStatus status;
  private long version;
  private int scriptCharacterCount;
  private String voiceName;
  private String languageCode;
  private String audioEncoding;
  private boolean providerRequestStarted;
  private String providerOperationName;
  private String providerOutputObject;
  private String audioPath;
  private Long fileSize;
  private String checksumSha256;
  private Long durationSeconds;
  private int attemptCount;
  private long reuseCount;
  private Instant leaseUntil;
  private Instant requestedAt;
  private Instant startedAt;
  private Instant completedAt;
  private Instant updatedAt;
  private String failureCode;
  private boolean retryable;

  @SuppressWarnings("unused")
  private Narration() {
  }

  public Narration(
      final String id,
      final NarrationContentType contentType,
      final String contentId,
      final int scriptCharacterCount,
      final String voiceName,
      final String languageCode,
      final String audioEncoding,
      final String providerOutputObject,
      final Instant now
  ) {
    this.id = id;
    this.contentType = contentType;
    this.contentId = contentId;
    this.fingerprint = id;
    this.status = NarrationStatus.QUEUED;
    this.version = 1;
    this.scriptCharacterCount = scriptCharacterCount;
    this.voiceName = voiceName;
    this.languageCode = languageCode;
    this.audioEncoding = audioEncoding;
    this.providerOutputObject = providerOutputObject;
    this.requestedAt = now;
    this.updatedAt = now;
    this.retryable = true;
  }

  public String id() {
    return id;
  }

  public NarrationContentType contentType() {
    return contentType;
  }

  public String contentId() {
    return contentId;
  }

  public String fingerprint() {
    return fingerprint;
  }

  public NarrationStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  public int scriptCharacterCount() {
    return scriptCharacterCount;
  }

  public String voiceName() {
    return voiceName;
  }

  public String languageCode() {
    return languageCode;
  }

  public String audioEncoding() {
    return audioEncoding;
  }

  public boolean providerRequestStarted() {
    return providerRequestStarted;
  }

  public String providerOperationName() {
    return providerOperationName;
  }

  public String providerOutputObject() {
    return providerOutputObject;
  }

  public String audioPath() {
    return audioPath;
  }

  public Long fileSize() {
    return fileSize;
  }

  public String checksumSha256() {
    return checksumSha256;
  }

  public Long durationSeconds() {
    return durationSeconds;
  }

  public int attemptCount() {
    return attemptCount;
  }

  public long reuseCount() {
    return reuseCount;
  }

  public Instant leaseUntil() {
    return leaseUntil;
  }

  public Instant requestedAt() {
    return requestedAt;
  }

  public Instant startedAt() {
    return startedAt;
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

  public void markProviderRequestStarted(final Instant now) {
    providerRequestStarted = true;
    failureCode = null;
    touch(now);
  }

  public void markProviderOperation(final String operationName, final Instant now) {
    providerOperationName = operationName;
    touch(now);
  }

  public void markReady(final NarrationStorage.StoredNarration stored, final Instant now) {
    status = NarrationStatus.READY;
    audioPath = stored.publicPath();
    fileSize = stored.fileSize();
    checksumSha256 = stored.checksumSha256();
    durationSeconds = stored.durationSeconds();
    leaseUntil = null;
    completedAt = now;
    failureCode = null;
    retryable = false;
    touch(now);
  }

  public void markFailed(final String code, final boolean canRetry, final Instant now) {
    status = NarrationStatus.FAILED;
    failureCode = code;
    retryable = canRetry;
    leaseUntil = null;
    clearAudio();
    touch(now);
  }

  public void markUncertain(final String code, final Instant now) {
    status = NarrationStatus.UNCERTAIN;
    failureCode = code;
    retryable = false;
    leaseUntil = null;
    touch(now);
  }

  public void markQueued(final Instant now) {
    status = NarrationStatus.QUEUED;
    retryable = true;
    leaseUntil = null;
    touch(now);
  }

  public void markStale(final Instant now) {
    status = NarrationStatus.STALE;
    retryable = false;
    leaseUntil = null;
    clearAudio();
    touch(now);
  }

  public void incrementReuse(final Instant now) {
    reuseCount++;
    touch(now);
  }

  public void extendLease(final Instant newLease, final Instant now) {
    leaseUntil = newLease;
    updatedAt = now;
  }

  public void claimed(final Instant lease, final Instant now) {
    status = NarrationStatus.PROCESSING;
    attemptCount++;
    leaseUntil = lease;
    startedAt = now;
    retryable = false;
    touch(now);
  }

  private void touch(final Instant now) {
    version++;
    updatedAt = now;
  }

  private void clearAudio() {
    audioPath = null;
    fileSize = null;
    checksumSha256 = null;
    durationSeconds = null;
  }
}
