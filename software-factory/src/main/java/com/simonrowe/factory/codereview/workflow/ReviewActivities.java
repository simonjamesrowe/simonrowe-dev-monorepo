package com.simonrowe.factory.codereview.workflow;

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

  @ActivityMethod
  void publishReview(PullRequestContext pullRequest, ReviewReport report, String statusCommentId);

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
}
