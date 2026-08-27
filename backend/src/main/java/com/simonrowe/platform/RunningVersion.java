package com.simonrowe.platform;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * This process's own version, read from the {@code build-info.properties} baked into the
 * image by the {@code bootBuildInfo} Gradle task.
 *
 * <p>{@link BuildProperties} is only auto-configured when that file is present, so it is
 * injected as {@code @Nullable}: a developer running {@code bootRun} from an IDE that
 * skipped the task must not get a startup failure, they get a "dev build".
 *
 * <p><b>{@code startedAt} is captured in the constructor, not from
 * {@code ApplicationReadyEvent}.</b> {@code ReleaseRecorder} listens to that event and
 * needs this value already populated; going through the event too would couple the two
 * beans through listener ordering to gain at most a second or two of accuracy.
 */
@Component
public class RunningVersion {

  private static final Logger LOG = LoggerFactory.getLogger(RunningVersion.class);
  private static final String SERVICE_NAME = "backend";
  private static final int SHORT_SHA_LENGTH = 7;

  private final BuildProperties buildProperties;
  private final Instant startedAt;

  @Autowired
  public RunningVersion(@Nullable final BuildProperties buildProperties) {
    this.buildProperties = buildProperties;
    this.startedAt = Instant.now();
  }

  /**
   * This process's version.
   *
   * @return the version; never null, always {@code reachable}
   */
  public ServiceVersion current() {
    return new ServiceVersion(
        SERVICE_NAME, commit(), shortCommit(), subject(), commitTime(), startedAt, true);
  }

  /**
   * The full commit SHA this artifact was built from.
   *
   * @return the SHA, or {@code unknown}
   */
  public String commit() {
    String value = property("commit");
    return value == null || value.isBlank() ? ServiceVersion.UNKNOWN_COMMIT : value;
  }

  /**
   * When this process started.
   *
   * @return the start instant
   */
  public Instant startedAt() {
    return startedAt;
  }

  /**
   * When the commit this artifact was built from was authored.
   *
   * @return the instant, or null when unknown
   */
  public Instant commitTime() {
    String value = property("commitTime");
    if (value == null || value.isBlank()) {
      return null;
    }
    long epochSeconds;
    try {
      epochSeconds = Long.parseLong(value.trim());
    } catch (final NumberFormatException e) {
      LOG.warn("Non-numeric commitTime in build-info.properties: {}", value, e);
      return null;
    }
    // The Gradle task writes 0 when git was unavailable, which keeps the build output
    // deterministic. Epoch is never a real commit time, so it means "unknown".
    return epochSeconds == 0L ? null : Instant.ofEpochSecond(epochSeconds);
  }

  private String shortCommit() {
    String full = commit();
    return ServiceVersion.UNKNOWN_COMMIT.equals(full)
        ? ServiceVersion.DEV_SHORT_COMMIT
        : full.substring(0, Math.min(SHORT_SHA_LENGTH, full.length()));
  }

  private String subject() {
    return property("commitSubject");
  }

  private String property(final String key) {
    return buildProperties == null ? null : buildProperties.get(key);
  }
}
