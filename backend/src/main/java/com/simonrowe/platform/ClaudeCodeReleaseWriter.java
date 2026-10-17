package com.simonrowe.platform;

import com.simonrowe.factoryadmin.ContentWritingClient;
import com.simonrowe.factoryadmin.ContentWritingClient.State;
import com.simonrowe.factoryadmin.ContentWritingClient.WritingJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks software-factory's content-writing queue for one release's note, and says whether to use
 * it, wait for it, or fall back to OpenAI.
 *
 * <p>No state is persisted. The job id is derived from the release, and the factory starts a job
 * at most once per id, so each sweep tick can simply ask again. The only memory is when this JVM
 * first saw each job running, which bounds how long a release waits for Claude Code before
 * OpenAI writes it instead; a restart resets that clock, which only means one more wait.
 *
 * <p>The factory is read before anything is submitted. Submitting restarts a job that failed, so
 * a sweep that submitted first would restart a rate-limited job on every tick and never see the
 * failure it should be falling back on.
 *
 * <p>Owner-scheduled background work only: see {@link ContentWritingClient} for why this path
 * must never serve anything a site visitor triggers.
 */
class ClaudeCodeReleaseWriter {

  private static final Logger LOG = LoggerFactory.getLogger(ClaudeCodeReleaseWriter.class);

  private final ContentWritingClient client;
  private final Clock clock;
  private final Duration maxWait;
  private final Map<String, Instant> firstSeenRunning = new ConcurrentHashMap<>();

  ClaudeCodeReleaseWriter(
      final ContentWritingClient client, final Clock clock, final Duration maxWait) {
    this.client = client;
    this.clock = clock;
    this.maxWait = maxWait;
  }

  /**
   * Advances one release's job by one step. Never throws.
   *
   * @param release the pending release
   * @param prompt the prompt OpenAI would be given, sent unchanged
   * @return what the sweep should do with this release this tick
   */
  Outcome attempt(final PlatformRelease release, final String prompt) {
    String jobId = jobId(release);
    WritingJob job;
    try {
      job = client.status(jobId);
      if (job.status() == State.NOT_FOUND) {
        job = client.submit(jobId, prompt);
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "Content-writing queue unavailable for release {}, falling back to OpenAI: {}",
          release.getShortSha(), e.getMessage());
      return Outcome.fallBack();
    }
    return switch (job.status()) {
      case COMPLETED -> completed(release, job);
      case FAILED -> {
        LOG.info(
            "Claude Code could not write release {}, falling back to OpenAI",
            release.getShortSha());
        yield Outcome.fallBack();
      }
      case RUNNING, NOT_FOUND -> running(release, jobId);
    };
  }

  private Outcome completed(final PlatformRelease release, final WritingJob job) {
    if (job.text() == null || job.text().isBlank()) {
      LOG.warn(
          "Claude Code returned no text for release {}, falling back to OpenAI",
          release.getShortSha());
      return Outcome.fallBack();
    }
    return Outcome.written(job.text());
  }

  /**
   * Waits within the bound, falls back past it. The first-seen time is kept after the bound has
   * passed, so a release whose OpenAI fallback also failed falls back straight away next tick
   * rather than waiting the whole bound again.
   */
  private Outcome running(final PlatformRelease release, final String jobId) {
    Instant now = clock.instant();
    Instant firstSeen = firstSeenRunning.computeIfAbsent(jobId, key -> now);
    if (Duration.between(firstSeen, now).compareTo(maxWait) >= 0) {
      LOG.warn(
          "Claude Code still writing release {} after {}, falling back to OpenAI",
          release.getShortSha(), maxWait);
      return Outcome.fallBack();
    }
    return Outcome.waiting();
  }

  /**
   * Drops the release's wait clock once it has a summary or has been given up on, so the map
   * holds only releases still pending.
   *
   * @param release the release that has left {@code PENDING}
   */
  void forget(final PlatformRelease release) {
    firstSeenRunning.remove(jobId(release));
  }

  /** How many releases this JVM is currently timing. Visible for tests. */
  int tracked() {
    return firstSeenRunning.size();
  }

  /**
   * One job per release and prompt version, so a prompt change writes a new note rather than
   * returning the old job's text.
   *
   * @param release the release
   * @return a job id within the factory's charset
   */
  static String jobId(final PlatformRelease release) {
    return "release-%s-%s"
        .formatted(release.getId(), ReleaseSummarySweep.SUMMARY_FORMAT_VERSION)
        .replaceAll("[^A-Za-z0-9._-]", "-");
  }

  /** What the sweep does with a release after asking the queue. */
  enum Action {
    WRITTEN,
    WAITING,
    FALL_BACK
  }

  /**
   * The answer for one release.
   *
   * @param action what to do
   * @param text the note, only for {@link Action#WRITTEN}
   */
  record Outcome(Action action, String text) {

    static Outcome written(final String text) {
      return new Outcome(Action.WRITTEN, text);
    }

    static Outcome waiting() {
      return new Outcome(Action.WAITING, null);
    }

    static Outcome fallBack() {
      return new Outcome(Action.FALL_BACK, null);
    }
  }
}
