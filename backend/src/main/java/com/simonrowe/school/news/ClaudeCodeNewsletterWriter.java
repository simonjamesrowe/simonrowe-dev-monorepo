package com.simonrowe.school.news;

import com.simonrowe.factoryadmin.ContentWritingClient;
import com.simonrowe.factoryadmin.ContentWritingClient.State;
import com.simonrowe.factoryadmin.ContentWritingClient.WritingJob;
import com.simonrowe.school.model.SchoolDocument;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks software-factory's content-writing queue to summarise one newsletter, and says whether to
 * use the text, wait for it, or fall back to OpenAI.
 *
 * <p>The same shape as {@code ClaudeCodeReleaseWriter}, and for the same reasons. No state is
 * persisted: the job id is derived from the newsletter and its text, and the factory starts a job
 * at most once per id, so each run simply asks again. The factory is read before anything is
 * submitted, because a submit restarts a failed job, and a caller that submitted first would
 * restart a rate-limited job every run and never see the failure it should fall back on.
 *
 * <p>Allowed on the subscription only because this is the owner's own scheduled job: the summary
 * is written on a schedule and stored, and nothing a visitor does starts one. See
 * {@link ContentWritingClient}.
 */
class ClaudeCodeNewsletterWriter {

  private static final Logger LOG = LoggerFactory.getLogger(ClaudeCodeNewsletterWriter.class);

  /** Bump with the prompt, so a prompt change writes new jobs rather than reading old ones. */
  static final String FORMAT_VERSION = "newsletter-v1";

  private final ContentWritingClient client;
  private final Clock clock;
  private final Duration maxWait;
  private final Map<String, Instant> firstSeenRunning = new ConcurrentHashMap<>();

  ClaudeCodeNewsletterWriter(
      final ContentWritingClient client, final Clock clock, final Duration maxWait) {
    this.client = client;
    this.clock = clock;
    this.maxWait = maxWait;
  }

  /**
   * Advances one newsletter's job by one step. Never throws.
   *
   * @param newsletter the newsletter's document
   * @param prompt the complete prompt
   * @return what to do with this newsletter on this run
   */
  Outcome attempt(final SchoolDocument newsletter, final String prompt) {
    final String jobId = jobId(newsletter);
    WritingJob job;
    try {
      job = client.status(jobId);
      if (job.status() == State.NOT_FOUND) {
        job = client.submit(jobId, prompt);
      }
    } catch (RuntimeException e) {
      LOG.warn("Content-writing queue unavailable for newsletter '{}', using OpenAI: {}",
          newsletter.title(), e.getMessage());
      return Outcome.fallBack();
    }
    return switch (job.status()) {
      case COMPLETED -> {
        firstSeenRunning.remove(jobId);
        yield job.text() == null || job.text().isBlank()
            ? Outcome.fallBack()
            : Outcome.written(job.text());
      }
      case FAILED -> {
        firstSeenRunning.remove(jobId);
        LOG.info("Claude Code could not summarise newsletter '{}', using OpenAI",
            newsletter.title());
        yield Outcome.fallBack();
      }
      case RUNNING, NOT_FOUND -> running(newsletter, jobId);
    };
  }

  /**
   * Waits within the bound and falls back past it. The first-seen time is kept after the bound,
   * so a newsletter whose OpenAI fallback also failed falls back straight away on the next run
   * instead of waiting the whole bound again.
   */
  private Outcome running(final SchoolDocument newsletter, final String jobId) {
    final Instant now = clock.instant();
    final Instant firstSeen = firstSeenRunning.computeIfAbsent(jobId, key -> now);
    if (Duration.between(firstSeen, now).compareTo(maxWait) >= 0) {
      LOG.warn("Claude Code still summarising newsletter '{}' after {}, using OpenAI",
          newsletter.title(), maxWait);
      return Outcome.fallBack();
    }
    return Outcome.waiting();
  }

  /** How many newsletters this JVM is currently timing. Visible for tests. */
  int tracked() {
    return firstSeenRunning.size();
  }

  /**
   * One job per newsletter text and prompt version: an edited newsletter has a new hash and so
   * gets a new job, rather than the factory handing back the summary of the old text.
   *
   * @param newsletter the newsletter's document
   * @return a job id within the factory's charset and 128-character limit
   */
  static String jobId(final SchoolDocument newsletter) {
    final String hash = newsletter.contentHash() == null ? "none" : newsletter.contentHash();
    return "newsletter-%s-%s-%s"
        .formatted(newsletter.id(), hash.substring(0, Math.min(16, hash.length())),
            FORMAT_VERSION)
        .replaceAll("[^A-Za-z0-9._-]", "-");
  }

  /** What to do with a newsletter after asking the queue. */
  enum Action {
    WRITTEN,
    WAITING,
    FALL_BACK
  }

  /**
   * The answer for one newsletter.
   *
   * @param action what to do
   * @param text the written text, only for {@link Action#WRITTEN}
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
