package com.simonrowe.platform;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes the release-notes paragraph for each release that does not have one yet.
 *
 * <p><b>Generation happens here, at ingest, and never on the read path.</b>
 * {@code GET /api/platform/releases} serves whatever is stored; a public endpoint that called
 * an LLM per request would be a cost and abuse problem, and {@code RateLimitInterceptor} does
 * not meter {@code /api/platform/**} (deliberately — the page issues two requests per view).
 *
 * <p>Batched rather than done in one pass at startup: a 50-release backfill in a single loop
 * would hold a thread for minutes and spike the bill in one burst. Three per tick every two
 * minutes clears the backfill in about 35 minutes.
 *
 * <p>Failure is never fatal to an entry. After {@code maxAttempts} the release is
 * {@code FAILED} and the page renders it from its commit subject alone.
 */
@Component
public class ReleaseSummarySweep {

  private static final Logger LOG = LoggerFactory.getLogger(ReleaseSummarySweep.class);

  private static final int MAX_FILES_IN_PROMPT = 40;

  /**
   * Versions {@link #SUMMARY_PROMPT}. Unlike {@code article-summary-v1} this does <em>not</em>
   * feed a document id — the id is the commit SHA — so bumping it does not invalidate stored
   * summaries. To regenerate after a prompt change, set every {@code READY} release back to
   * {@code PENDING} deliberately.
   */
  static final String SUMMARY_FORMAT_VERSION = "platform-release-v1";

  private static final String SUMMARY_PROMPT = """
      Write a short release note for one change to a personal website's codebase, for a \
      technically literate reader browsing a public changelog.

      Requirements:
      - One paragraph, 2 to 4 sentences. Plain prose, no Markdown, no heading, no bullet \
      list, no links.
      - Say what changed and why it matters to someone using or reading about the site. \
      Lead with the effect, not the mechanism.
      - Do not restate the commit subject verbatim, and do not open with "This commit" or \
      "This release".
      - Be concrete where the material allows: name the feature, page or component that \
      changed. Use the file list as evidence of scope, but do not list file names.
      - Say nothing the material below does not support. If it is thin, write one short \
      factual sentence rather than padding.

      Commit subject: %s

      Commit message body:
      %s

      Files changed (%d total, showing up to %d):
      %s
      """;

  private final PlatformReleaseRepository repository;
  private final Ai ai;
  private final boolean enabled;
  private final int batchSize;
  private final int maxAttempts;
  private final String model;

  /**
   * Creates the sweep.
   *
   * @param repository the release repository
   * @param ai the Embabel inline-LLM entry point
   * @param enabled whether the sweep is allowed to run
   * @param batchSize how many releases to summarise per tick
   * @param maxAttempts how many failed attempts a release may accrue before giving up
   * @param model the LLM model name to call
   */
  public ReleaseSummarySweep(
      final PlatformReleaseRepository repository,
      final Ai ai,
      @Value("${platform.releases.summaries.enabled:true}") final boolean enabled,
      @Value("${platform.releases.summaries.batch-size:3}") final int batchSize,
      @Value("${platform.releases.summaries.max-attempts:3}") final int maxAttempts,
      @Value("${platform.releases.summaries.model}") final String model) {
    this.repository = repository;
    this.ai = ai;
    this.enabled = enabled;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.model = model;
  }

  /** Runs the sweep on a fixed delay, so a slow model call cannot overlap the next tick. */
  @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT2M")
  public void scheduledSweep() {
    try {
      sweep();
    } catch (RuntimeException e) {
      LOG.warn("Release summary sweep failed: {}", e.getMessage());
    }
  }

  /**
   * Summarises up to {@code batchSize} pending releases.
   *
   * @return how many were summarised in this tick
   */
  public int sweep() {
    if (!enabled) {
      return 0;
    }
    List<PlatformRelease> pending = repository.findPending(batchSize);
    int summarised = 0;
    for (PlatformRelease release : pending) {
      if (summarise(release)) {
        summarised++;
      }
    }
    return summarised;
  }

  private boolean summarise(final PlatformRelease release) {
    try {
      String completion = ai.withLlm(model)
          .respond(List.of(new UserMessage(prompt(release))))
          .getContent();
      if (completion == null || completion.isBlank()) {
        LOG.warn("Empty completion summarising release {}", release.getShortSha());
        return recordFailedAttempt(release);
      }
      release.setSummary(completion.trim());
      release.setSummaryStatus(ReleaseSummaryStatus.READY);
      release.setSummaryAttempts(release.getSummaryAttempts() + 1);
      release.setUpdatedAt(Instant.now());
      repository.save(release);
      LOG.info("Summarised release {} ({})", release.getShortSha(), SUMMARY_FORMAT_VERSION);
      return true;
    } catch (RuntimeException e) {
      LOG.warn("Failed to summarise release {}: {}", release.getShortSha(), e.getMessage());
      return recordFailedAttempt(release);
    }
  }

  /**
   * Counts an attempt and gives up at the limit.
   *
   * @param release the release whose attempt is being recorded
   * @return always false — nothing was summarised
   */
  private boolean recordFailedAttempt(final PlatformRelease release) {
    int attempts = release.getSummaryAttempts() + 1;
    release.setSummaryAttempts(attempts);
    release.setSummaryStatus(
        attempts >= maxAttempts ? ReleaseSummaryStatus.FAILED : ReleaseSummaryStatus.PENDING);
    release.setUpdatedAt(Instant.now());
    repository.save(release);
    return false;
  }

  private static String prompt(final PlatformRelease release) {
    List<String> files = release.getFilesChanged();
    String shown = String.join(
        "\n", files.subList(0, Math.min(MAX_FILES_IN_PROMPT, files.size())));
    return String.format(
        SUMMARY_PROMPT,
        release.getSubject(),
        release.getBody() == null || release.getBody().isBlank() ? "(none)" : release.getBody(),
        files.size(),
        MAX_FILES_IN_PROMPT,
        shown.isBlank() ? "(none recorded)" : shown);
  }
}
