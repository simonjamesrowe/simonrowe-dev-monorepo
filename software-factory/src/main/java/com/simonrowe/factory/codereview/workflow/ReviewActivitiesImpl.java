package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.agent.ReviewEngine;
import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.github.GitHubGateway;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

/** Spring-managed activity adapter for GitHub and the configured review engine. */
@Component
@ActivityImpl(taskQueues = CodeReviewTaskQueues.REVIEWS)
public class ReviewActivitiesImpl implements ReviewActivities {

  private final GitHubGateway gitHubGateway;
  private final ReviewEngine reviewEngine;

  public ReviewActivitiesImpl(
      final GitHubGateway gitHubGateway, final ReviewEngine reviewEngine) {
    this.gitHubGateway = gitHubGateway;
    this.reviewEngine = reviewEngine;
  }

  @Override
  public String openStatusComment(final ReviewRequest request) {
    return gitHubGateway.openStatusComment(request);
  }

  @Override
  public PullRequestContext loadPullRequest(final ReviewRequest request) {
    return gitHubGateway.loadPullRequest(request);
  }

  @Override
  public ReviewReport runReview(final PullRequestContext pullRequest) {
    return reviewEngine.review(
        pullRequest, detail -> Activity.getExecutionContext().heartbeat(detail));
  }

  @Override
  public void publishReview(
      final PullRequestContext pullRequest,
      final ReviewReport report,
      final String statusCommentId) {
    gitHubGateway.publishReview(pullRequest, report, statusCommentId);
  }

  @Override
  public void publishFailure(
      final ReviewRequest request, final String statusCommentId, final ReviewFailure failure) {
    gitHubGateway.publishFailure(request, statusCommentId, failure);
  }
}
