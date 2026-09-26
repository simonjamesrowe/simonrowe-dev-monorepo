package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.domain.MergeDecision;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** All non-deterministic I/O is kept outside workflow code behind activities. */
@ActivityInterface
public interface ReviewActivities {

  @ActivityMethod
  String openStatusComment(ReviewRequest request);

  @ActivityMethod
  PullRequestContext loadPullRequest(ReviewRequest request);

  @ActivityMethod
  ReviewReport runReview(PullRequestContext pullRequest);

  /**
   * Publishes the review, with a line in the summary saying what happened to auto-merge.
   *
   * <p>{@code autoMerge} is null for a workflow started before auto-merge existed. Temporal fills
   * a missing trailing argument with null, so such a run still publishes, just without that line.
   */
  @ActivityMethod
  void publishReview(
      PullRequestContext pullRequest,
      ReviewReport report,
      String statusCommentId,
      MergeDecision autoMerge);

  @ActivityMethod
  void publishFailure(ReviewRequest request, String statusCommentId, ReviewFailure failure);

  /**
   * Opens the {@code Code Review} check run and returns its id.
   *
   * <p>Takes {@link PullRequestContext} rather than {@link ReviewRequest} because a check run must
   * be attached to a commit, and the head SHA is only certain once the pull request has been
   * loaded — {@code ReviewRequest.expectedHeadSha} is nullable on the manual-review path.
   */
  @ActivityMethod
  String openCheckRun(PullRequestContext pullRequest, String workflowId);

  @ActivityMethod
  void completeCheckRun(PullRequestContext pullRequest, String checkRunId, ReviewReport report);

  @ActivityMethod
  void failCheckRun(PullRequestContext pullRequest, String checkRunId, ReviewFailure failure);

  /**
   * Withdraws an auto-merge a bot armed on an earlier commit. A person's arm is never touched.
   *
   * <p>Runs before every published review, because GitHub keeps auto-merge armed across pushes
   * by anyone with write access. Without it, a "yes" given for a docs-only commit would carry
   * over to a later push that edits {@code docker-compose.prod.yml}, and the only thing that
   * changed would be what merges.
   */
  @ActivityMethod
  void withdrawAutoMerge(PullRequestContext pullRequest);

  /** Decides without acting: reads GitHub and applies the rules, but arms nothing. */
  @ActivityMethod
  MergeDecision decideAutoMerge(PullRequestContext pullRequest, ReviewReport report);

  /**
   * Decides, then arms squash auto-merge if every rule passes. Returns what actually happened.
   * GitHub refusing to arm is reported as {@code ARM_FAILED} rather than thrown.
   */
  @ActivityMethod
  MergeDecision armAutoMerge(PullRequestContext pullRequest, ReviewReport report);
}
