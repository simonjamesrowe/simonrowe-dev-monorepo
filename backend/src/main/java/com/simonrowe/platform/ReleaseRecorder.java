package com.simonrowe.platform;

import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code platform_releases} in step with {@code main}, and records which commit this
 * backend was built from.
 *
 * <p>Polls {@link CommitHistory} on a fixed delay rather than seeding once at startup. The
 * history used to be baked into the backend image, which was only correct while every merge
 * rebuilt the backend; with Publish skipping unchanged images, a frontend-only merge has to
 * reach the changelog without the backend restarting at all.
 *
 * <p>Insert-only: a release already present is left completely alone, because its summary cost
 * an LLM call. The single exception is promoting an existing {@code PUBLISHED_HISTORY} record
 * to {@code RUNNING} once this backend is running on it — the evidence that upgrades
 * "published" to "ran". That promotion touches {@code source} and nothing else. Note that
 * {@code RUNNING} now marks the commit the backend was <em>built</em> from, which after a merge
 * that touched only the frontend is older than the newest commit deployed.
 *
 * <p><b>Why this is not a Mongock change unit</b> despite the repo's Mongock-first rule: these
 * are derived, self-healing records that a restore drops and this component re-establishes on
 * the next poll. Seeding in a change unit would also mean a change unit doing network I/O and
 * feeding LLM calls, run against the shared Testcontainers Mongo in every integration test.
 */
@Component
public class ReleaseRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(ReleaseRecorder.class);

  private final RunningVersion runningVersion;
  private final CommitHistory history;
  private final PlatformReleaseRepository repository;
  private final boolean enabled;
  private final int maxFileLookups;

  /**
   * Creates the recorder.
   *
   * @param runningVersion this process's version
   * @param history where {@code main}'s commits are read from
   * @param repository where releases are stored
   * @param enabled whether polling is switched on
   * @param maxFileLookups how many per-commit file lookups one poll may spend. Bounds what a
   *     restore into an empty collection costs against GitHub's anonymous limit of 60 requests
   *     an hour: a 50-commit backfill spreads over three polls instead of exhausting it in one
   */
  public ReleaseRecorder(
      final RunningVersion runningVersion,
      final CommitHistory history,
      final PlatformReleaseRepository repository,
      @Value("${platform.releases.history.enabled:true}") final boolean enabled,
      @Value("${platform.releases.history.max-file-lookups:20}") final int maxFileLookups) {
    this.runningVersion = runningVersion;
    this.history = history;
    this.repository = repository;
    this.enabled = enabled;
    this.maxFileLookups = maxFileLookups;
  }

  /** Polls on a fixed delay. Failure here must never stop the application from serving. */
  @Scheduled(
      initialDelayString = "${platform.releases.history.initial-delay:PT20S}",
      fixedDelayString = "${platform.releases.history.poll-interval:PT5M}")
  public void scheduledPoll() {
    if (!enabled) {
      return;
    }
    try {
      int inserted = record();
      if (inserted > 0) {
        LOG.info("Release history: {} new release(s) recorded", inserted);
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not read release history: {}", e.getMessage());
    }
  }

  /**
   * Stores every listed commit not already held, newest first, and marks the running one.
   *
   * <p>A failed file lookup ends the poll rather than storing the commit without its files:
   * the file list feeds the release note, and a record is never revisited once stored. The
   * commits not reached are picked up by the next poll.
   *
   * @return how many records were inserted
   */
  public int record() {
    Instant now = Instant.now();
    String runningSha = runningVersion.commit();
    int inserted = 0;
    int lookups = 0;
    for (MainCommit commit : history.recent()) {
      ReleaseSource source =
          commit.sha().equals(runningSha) ? ReleaseSource.RUNNING : ReleaseSource.PUBLISHED_HISTORY;
      if (repository.existsById(commit.sha())) {
        if (source == ReleaseSource.RUNNING) {
          promoteToRunning(commit.sha());
        }
        continue;
      }
      if (lookups >= maxFileLookups) {
        break;
      }
      lookups++;
      MainCommit complete = commit.withFiles(history.filesChanged(commit.sha()));
      if (insert(complete, source, now)) {
        inserted++;
      }
    }
    return inserted;
  }

  /**
   * Inserts a release, treating a concurrent insert of the same SHA as success-by-someone-else.
   *
   * @return true when this call created the record
   */
  private boolean insert(
      final MainCommit commit, final ReleaseSource source, final Instant now) {
    try {
      repository.insert(PlatformRelease.fromCommit(commit, source, now));
      return true;
    } catch (DuplicateKeyException e) {
      // The _id is the SHA precisely so this race resolves itself.
      return false;
    }
  }

  private void promoteToRunning(final String sha) {
    Optional<PlatformRelease> stored = repository.findById(sha);
    if (stored.isEmpty() || stored.get().getSource() == ReleaseSource.RUNNING) {
      return;
    }
    PlatformRelease release = stored.get();
    release.setSource(ReleaseSource.RUNNING);
    repository.save(release);
    LOG.info("Release {} promoted to RUNNING: this backend was built from it",
        release.getShortSha());
  }
}
