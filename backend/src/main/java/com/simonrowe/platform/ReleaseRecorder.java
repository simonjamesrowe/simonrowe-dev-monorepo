package com.simonrowe.platform;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Seeds {@code platform_releases} from the history baked into this image, and records that this
 * build booted.
 *
 * <p>Runs on every startup and is insert-only: a release already present is left completely
 * alone, because its summary cost an LLM call. The single exception is promoting an existing
 * {@code PUBLISHED_HISTORY} record to {@code RUNNING} when this build boots on it — the history
 * is baked before the deploy, so the running SHA is normally already there from an earlier
 * boot, and booting on it is the evidence that upgrades "published" to "ran". That promotion
 * touches {@code source} and nothing else.
 *
 * <p><b>Why this is not a Mongock change unit</b> despite the repo's Mongock-first rule: these
 * are derived, self-healing records that a restore drops and this component re-establishes on
 * the next boot. Seeding in a change unit would also mean a change unit whose records feed LLM
 * calls, run against the shared Testcontainers Mongo in every integration test.
 */
@Component
public class ReleaseRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(ReleaseRecorder.class);

  private final RunningVersion runningVersion;
  private final Supplier<List<BakedRelease>> history;
  private final PlatformReleaseRepository repository;

  @Autowired
  public ReleaseRecorder(
      final RunningVersion runningVersion,
      final BakedReleaseHistory history,
      final PlatformReleaseRepository repository) {
    this(runningVersion, history::releases, repository);
  }

  /**
   * Test seam taking the history as a supplier, so a test can inject commits without a
   * classpath resource.
   *
   * @param runningVersion this process's version
   * @param history supplies the baked commits
   * @param repository where releases are stored
   */
  ReleaseRecorder(
      final RunningVersion runningVersion,
      final Supplier<List<BakedRelease>> history,
      final PlatformReleaseRepository repository) {
    this.runningVersion = runningVersion;
    this.history = history;
    this.repository = repository;
  }

  /** Seeds on startup. Failure here must never stop the application from serving. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      int inserted = record();
      LOG.info("Release history seeded: {} new release(s) recorded", inserted);
    } catch (RuntimeException e) {
      LOG.warn("Could not seed release history: {}", e.getMessage());
    }
  }

  /**
   * Seeds every baked release not already stored, and marks the running one.
   *
   * @return how many records were inserted
   */
  public int record() {
    Instant now = Instant.now();
    String runningSha = runningVersion.commit();
    int inserted = 0;
    for (BakedRelease baked : history.get()) {
      ReleaseSource source =
          baked.sha().equals(runningSha) ? ReleaseSource.RUNNING : ReleaseSource.PUBLISHED_HISTORY;
      if (insert(baked, source, now)) {
        inserted++;
      } else if (source == ReleaseSource.RUNNING) {
        promoteToRunning(baked.sha());
      }
    }
    return inserted;
  }

  /**
   * Inserts a release, treating an existing row as success-by-someone-else.
   *
   * @return true when this call created the record
   */
  private boolean insert(
      final BakedRelease baked, final ReleaseSource source, final Instant now) {
    if (repository.existsById(baked.sha())) {
      return false;
    }
    try {
      repository.insert(PlatformRelease.fromBaked(baked, source, now));
      return true;
    } catch (DuplicateKeyException e) {
      // Another instance inserted it between the check and the insert. Not an error:
      // the _id is the SHA precisely so this race resolves itself.
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
    LOG.info("Release {} promoted to RUNNING: this build booted on it", release.getShortSha());
  }
}
