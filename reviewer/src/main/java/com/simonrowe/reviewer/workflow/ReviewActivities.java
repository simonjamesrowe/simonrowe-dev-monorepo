package com.simonrowe.reviewer.workflow;

import com.simonrowe.reviewer.domain.PullRequestContext;
import com.simonrowe.reviewer.domain.ReviewReport;
import com.simonrowe.reviewer.domain.ReviewRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** All non-deterministic I/O is kept outside workflow code behind activities. */
@ActivityInterface
public interface ReviewActivities {

  @ActivityMethod
  PullRequestContext loadPullRequest(ReviewRequest request);

  @ActivityMethod
  ReviewReport runReview(PullRequestContext pullRequest);

  @ActivityMethod
  void publishReview(PullRequestContext pullRequest, ReviewReport report);
}
