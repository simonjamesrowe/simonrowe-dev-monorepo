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
  String publishAck(ReviewRequest request);

  @ActivityMethod
  PullRequestContext loadPullRequest(ReviewRequest request);

  @ActivityMethod
  ReviewReport runReview(PullRequestContext pullRequest);

  @ActivityMethod
  void publishReview(PullRequestContext pullRequest, ReviewReport report);

  @ActivityMethod
  void resolveAck(ReviewRequest request, String ackCommentId);

  @ActivityMethod
  void publishFailure(ReviewRequest request, String ackCommentId, ReviewFailure failure);
}
