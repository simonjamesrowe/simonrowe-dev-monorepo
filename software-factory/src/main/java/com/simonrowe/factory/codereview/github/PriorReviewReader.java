package com.simonrowe.factory.codereview.github;

import com.simonrowe.factory.codereview.domain.ExistingThread;
import com.simonrowe.factory.codereview.domain.PriorFinding;
import com.simonrowe.factory.codereview.domain.PriorReview;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Reads what this reviewer already said on a pull request, and which of it a person declined.
 *
 * <p>Brunel learnt the same two lessons this exists for. Without the open findings, a fresh model
 * run rewords a standing finding's title, and the fingerprint calls that one finding resolved and
 * another new: Brunel saw one issue get a new identity on eleven pushes. Without the declined
 * ones, a person resolving a thread to decline it was overruled on the next push, which posted the
 * finding again as a regression and, with conversation resolution required, blocked the merge on
 * a question already answered.
 */
@Component
public class PriorReviewReader {

  /** The permissions that may decline a finding. GitHub reports {@code maintain} as write. */
  private static final Set<String> TRUSTED = Set.of("admin", "maintain", "write");

  private final ReviewThreadGateway threads;
  private final AutoMergeGateway permissions;

  public PriorReviewReader(final ReviewThreadGateway threads, final AutoMergeGateway permissions) {
    this.threads = threads;
    this.permissions = permissions;
  }

  /** The pull request's conversations and this reviewer's open and declined findings. */
  public PriorReview read(final PullRequestContext pullRequest) {
    List<ExistingThread> existing = threads.fetchThreads(pullRequest);
    Map<String, String> permissionCache = new HashMap<>();
    return of(
        existing,
        login ->
            permissionCache.computeIfAbsent(
                login, key -> permissions.permission(pullRequest, key)));
  }

  /**
   * Classifies the threads. Pure, so the table is tested without HTTP.
   *
   * <p>Decline wins when one fingerprint has several threads. Before this change a declined
   * finding was posted again on the next push, so a pull request can carry both a declined thread
   * and a newer open one for the same finding; the person's decision is the one to keep.
   *
   * <p>Only a person with write access can decline. A pull request author without it can resolve
   * conversations on their own pull request, and that must not be enough to clear a finding the
   * merge gate is holding.
   *
   * <p>And a decline needs a reply from a person in the thread. The documented way to clear a
   * finding you fixed is to push and resolve, with no reply; the way to decline is to reply with
   * the reason and then resolve. Reading every resolution as a decline would stop the reviewer
   * re-checking a fix that did not work, and would let resolving a critical finding clear the gate
   * on the strength of a fix nobody verified. A thread resolved without a reply is treated as
   * this reviewer's own resolutions are: if the finding comes back, it is a regression.
   */
  static PriorReview of(
      final List<ExistingThread> existing, final Function<String, String> permissionOf) {
    Map<String, PriorFinding> byFingerprint = new LinkedHashMap<>();
    for (ExistingThread thread : existing) {
      if (thread.fingerprint() == null || thread.finding() == null) {
        continue;
      }
      PriorFinding candidate = null;
      if (!thread.resolved()) {
        candidate =
            new PriorFinding(
                thread.fingerprint(),
                thread.finding(),
                PriorFinding.State.OPEN,
                thread.raisedAt(),
                null);
      } else if (thread.resolvedByPerson()
          && thread.hasNonBotReply()
          && TRUSTED.contains(permissionOf.apply(thread.resolvedBy()))) {
        candidate =
            new PriorFinding(
                thread.fingerprint(),
                thread.finding(),
                PriorFinding.State.DECLINED,
                thread.raisedAt(),
                thread.resolvedBy());
      }
      if (candidate == null) {
        continue;
      }
      PriorFinding current = byFingerprint.get(candidate.fingerprint());
      if (current == null || candidate.state() == PriorFinding.State.DECLINED) {
        byFingerprint.put(candidate.fingerprint(), candidate);
      }
    }
    return new PriorReview(existing, new ArrayList<>(byFingerprint.values()));
  }
}
