package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.agent.ReviewEngine;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.github.CheckRunGateway;
import com.simonrowe.factory.codereview.github.GitHubGateway;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring-managed activity adapter for GitHub and the configured review engine.
 *
 * <p><strong>The condition is what keeps {@code deployer} out of the {@code code-review}
 * queue.</strong> Both containers run this image, and {@code @WorkflowImpl} classpath scanning is
 * unconditional, so both register a code-review <em>workflow</em>-task poller. That much is
 * harmless — a workflow implementation only schedules activities. An <em>activity</em> poller
 * executes them, and {@code deployer} deliberately holds no GitHub App credential.
 *
 * <p>Without the condition the failure is intermittent rather than loud, because Temporal hands
 * each activity to whichever worker polls first: roughly half of all reviews died at {@code
 * GitHubCredentials.mintInstallationToken} with {@code GitHub App token request failed} wrapping a
 * bare {@code UnresolvedAddressException} — no App configuration means no host to resolve. It
 * reads as flaky DNS, and it is not. Two things make that especially misleading: the errors appear
 * only in the {@code deployer}'s log and not in {@code software-factory}'s, and {@code getent} and
 * {@code curl} from inside {@code software-factory} succeed the whole time. Because the routing is
 * per activity rather than per review, a single run could clear {@code REVIEWING} and then fail in
 * {@code PUBLISHING}. {@code ReviewWorkerRegistrationTest} exists to stop this returning.
 *
 * <p><strong>Defaults on, unlike {@code factory.deploy.enabled}, and the asymmetry is
 * deliberate.</strong> That flag guards the Docker socket, so opt-in is the safe default. This one
 * guards nothing: {@code deployer} holds no credential to leak, so a default of off would buy no
 * safety while making a missing or overridden {@code FACTORY_CODEREVIEW_ENABLED} silently disable
 * code review everywhere — with the repository's merge gate requiring the {@code Code Review}
 * check, that would block every pull request and look like an outage. Defaulting on fails toward
 * the visible, already-diagnosed problem instead of the silent one.
 *
 * <p>Deliberately not a field on {@link CodeReviewProperties}: nothing reads this value at
 * runtime, it exists only for the component scanner, and widening that record would touch twenty
 * test call sites for no gain.
 */
@Component
@ActivityImpl(taskQueues = CodeReviewTaskQueues.REVIEWS)
@ConditionalOnProperty(
    name = "factory.codereview.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReviewActivitiesImpl implements ReviewActivities {

  private final GitHubGateway gitHubGateway;
  private final CheckRunGateway checkRunGateway;
  private final ReviewEngine reviewEngine;

  public ReviewActivitiesImpl(
      final GitHubGateway gitHubGateway,
      final CheckRunGateway checkRunGateway,
      final ReviewEngine reviewEngine) {
    this.gitHubGateway = gitHubGateway;
    this.checkRunGateway = checkRunGateway;
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

  @Override
  public String openCheckRun(final PullRequestContext pullRequest, final String workflowId) {
    return checkRunGateway.open(pullRequest, workflowId);
  }

  @Override
  public void completeCheckRun(
      final PullRequestContext pullRequest,
      final String checkRunId,
      final ReviewReport report) {
    checkRunGateway.complete(pullRequest, checkRunId, report);
  }

  @Override
  public void failCheckRun(
      final PullRequestContext pullRequest,
      final String checkRunId,
      final ReviewFailure failure) {
    checkRunGateway.fail(pullRequest, checkRunId, failure);
  }
}
