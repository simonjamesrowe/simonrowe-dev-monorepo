package com.simonrowe.reviewer.workflow;

import com.simonrowe.reviewer.agent.ReviewEngine;
import com.simonrowe.reviewer.config.ReviewerTaskQueues;
import com.simonrowe.reviewer.domain.PullRequestContext;
import com.simonrowe.reviewer.domain.ReviewReport;
import com.simonrowe.reviewer.domain.ReviewRequest;
import com.simonrowe.reviewer.github.GitHubGateway;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

/** Spring-managed activity adapter for GitHub and the configured review engine. */
@Component
@ActivityImpl(taskQueues = ReviewerTaskQueues.REVIEWS)
public class ReviewActivitiesImpl implements ReviewActivities {

  private final GitHubGateway gitHubGateway;
  private final ReviewEngine reviewEngine;

  public ReviewActivitiesImpl(
      final GitHubGateway gitHubGateway, final ReviewEngine reviewEngine) {
    this.gitHubGateway = gitHubGateway;
    this.reviewEngine = reviewEngine;
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
  public void publishReview(final PullRequestContext pullRequest, final ReviewReport report) {
    gitHubGateway.publishReview(pullRequest, report);
  }
}
