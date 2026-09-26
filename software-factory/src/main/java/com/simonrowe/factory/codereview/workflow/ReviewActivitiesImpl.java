package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.agent.ReviewEngine;
import com.simonrowe.factory.codereview.config.AutoMergeProperties;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.AutoMergePolicy;
import com.simonrowe.factory.codereview.domain.AutoMergeState;
import com.simonrowe.factory.codereview.domain.MergeDecision;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.github.AutoMergeGateway;
import com.simonrowe.factory.codereview.github.CheckRunGateway;
import com.simonrowe.factory.codereview.github.GitHubGateway;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring-managed activity adapter for GitHub and the configured review engine.
 *
 * <p><strong>The condition is what keeps {@code deployer} out of the {@code code-review}
 * activity queue.</strong> Both containers run this image. Since #193 only {@code
 * software-factory} polls the code-review <em>workflow</em> queue (the Spring profile picks the
 * workflow packages), but activity registration is decided here. An <em>activity</em> poller is
 * what executes a step, and a review step clones pull request content and runs an agent over it,
 * which has no business in the one container holding the Docker socket.
 *
 * <p>{@code deployer} does hold the GitHub App credential today — {@code DeployReportGateway}
 * posts deploy write-ups with it. It did not when this condition was added, which is how the
 * failure below presented. Without the condition the failure is intermittent rather than loud,
 * because Temporal hands each activity to whichever worker polls first: roughly half of all
 * reviews died at {@code GitHubCredentials.mintInstallationToken} with {@code GitHub App token
 * request failed} wrapping a bare {@code UnresolvedAddressException} — no App configuration meant
 * no host to resolve. It
 * reads as flaky DNS, and it is not. Two things make that especially misleading: the errors appear
 * only in the {@code deployer}'s log and not in {@code software-factory}'s, and {@code getent} and
 * {@code curl} from inside {@code software-factory} succeed the whole time. Because the routing is
 * per activity rather than per review, a single run could clear {@code REVIEWING} and then fail in
 * {@code PUBLISHING}. {@code ReviewWorkerRegistrationTest} exists to stop this returning.
 *
 * <p><strong>Defaults on, unlike {@code factory.deploy.enabled}, and the asymmetry is
 * deliberate.</strong> That flag guards the Docker socket, so opt-in is the safe default. This one
 * guards no credential — the review activities need only what both containers already hold — so a
 * default of off would buy no safety while making a missing or overridden
 * {@code FACTORY_CODEREVIEW_ENABLED} silently disable code review everywhere — with the
 * repository's merge gate requiring the {@code Code Review} check, that would block every pull request and look like an outage. Defaulting on fails toward
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

  private static final Logger LOGGER = LoggerFactory.getLogger(ReviewActivitiesImpl.class);

  private final GitHubGateway gitHubGateway;
  private final CheckRunGateway checkRunGateway;
  private final ReviewEngine reviewEngine;
  private final AutoMergeGateway autoMergeGateway;
  private final AutoMergeProperties autoMergeProperties;

  public ReviewActivitiesImpl(
      final GitHubGateway gitHubGateway,
      final CheckRunGateway checkRunGateway,
      final ReviewEngine reviewEngine,
      final AutoMergeGateway autoMergeGateway,
      final AutoMergeProperties autoMergeProperties) {
    this.gitHubGateway = gitHubGateway;
    this.checkRunGateway = checkRunGateway;
    this.reviewEngine = reviewEngine;
    this.autoMergeGateway = autoMergeGateway;
    this.autoMergeProperties = autoMergeProperties;
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
      final String statusCommentId,
      final MergeDecision autoMerge) {
    gitHubGateway.publishReview(pullRequest, report, statusCommentId, autoMerge);
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

  @Override
  public void withdrawAutoMerge(final PullRequestContext pullRequest) {
    AutoMergeState state = autoMergeGateway.readState(pullRequest);
    if (state.autoMergeArmedByBot()) {
      autoMergeGateway.disable(pullRequest, state.nodeId());
      LOGGER.info("Withdrew bot-armed auto-merge on {} ahead of re-review", pullRequest.slug());
    }
  }

  @Override
  public MergeDecision decideAutoMerge(
      final PullRequestContext pullRequest, final ReviewReport report) {
    return decide(pullRequest, report, autoMergeGateway.readState(pullRequest));
  }

  @Override
  public MergeDecision armAutoMerge(
      final PullRequestContext pullRequest, final ReviewReport report) {
    AutoMergeState state = autoMergeGateway.readState(pullRequest);
    MergeDecision decision = decide(pullRequest, report, state);
    if (decision.outcome() != MergeDecision.Outcome.ELIGIBLE) {
      return decision;
    }
    if (state.autoMergeArmed()) {
      // This review withdrew any bot arm before it started, so a bot arm now is this activity's
      // own, from an attempt whose response was lost — GitHub would reject arming it twice. A
      // person's arm is theirs, and is left exactly as it is.
      return state.autoMergeArmedByBot()
          ? MergeDecision.armed()
          : MergeDecision.ineligible(
              "a person had already armed it, so the reviewer left it alone");
    }
    try {
      autoMergeGateway.enable(pullRequest, state.nodeId(), pullRequest.headSha());
    } catch (IllegalStateException exception) {
      // Reported, not thrown: nothing is armed, which is the safe outcome, and failing the review
      // over it would turn a merge a human can still perform into a red check.
      LOGGER.warn("Could not arm auto-merge on {}", pullRequest.slug(), exception);
      return MergeDecision.armFailed(exception.getMessage());
    }
    LOGGER.info("Armed auto-merge on {} at {}", pullRequest.slug(), pullRequest.headSha());
    return MergeDecision.armed();
  }

  private MergeDecision decide(
      final PullRequestContext pullRequest,
      final ReviewReport report,
      final AutoMergeState state) {
    return AutoMergePolicy.decide(
        autoMergeProperties.enabled(),
        state,
        report,
        pullRequest.headSha(),
        () -> autoMergeGateway.listFiles(pullRequest, state.changedFiles()));
  }
}
