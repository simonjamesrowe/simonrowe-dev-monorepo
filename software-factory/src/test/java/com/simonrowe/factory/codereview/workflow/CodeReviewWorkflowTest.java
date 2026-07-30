package com.simonrowe.factory.codereview.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewPhase;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.domain.ReviewResult;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CodeReviewWorkflowTest {

  @Test
  void orchestratesLoadReviewAndIdempotentPublish() {
    AtomicBoolean published = new AtomicBoolean();
    ReviewReport expected =
        new ReviewReport(
            "One concrete problem.",
            Verdict.COMMENT,
            List.of(
                new ReviewFinding(
                    Severity.WARNING,
                    "src/App.java",
                    12,
                    "Null result is dereferenced",
                    "The new null branch reaches this dereference.",
                    "Return before dereferencing.")));

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(CodeReviewTaskQueues.REVIEWS);
      worker.registerWorkflowImplementationTypes(CodeReviewWorkflowImpl.class);
      worker.registerActivitiesImplementations(new FakeActivities(expected, published));
      environment.start();

      CodeReviewWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  CodeReviewWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CodeReviewTaskQueues.REVIEWS)
                      .setWorkflowId("review-test")
                      .build());
      ReviewResult result =
          workflow.review(new ReviewRequest("owner", "repo", 7, "head-sha", 123L, true));

      assertThat(result.report()).isEqualTo(expected);
      assertThat(result.published()).isTrue();
      assertThat(published).isTrue();
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
      assertThat(workflow.progress().headSha()).isEqualTo("head-sha");
    }
  }

  private record FakeActivities(ReviewReport report, AtomicBoolean published)
      implements ReviewActivities {

    @Override
    public PullRequestContext loadPullRequest(final ReviewRequest request) {
      return new PullRequestContext(
          request.owner(),
          request.repository(),
          request.pullNumber(),
          "Title",
          "Body",
          "https://github.com/owner/repo.git",
          "base-sha",
          "head-sha",
          request.installationId());
    }

    @Override
    public ReviewReport runReview(final PullRequestContext pullRequest) {
      return report;
    }

    @Override
    public void publishReview(
        final PullRequestContext pullRequest, final ReviewReport reviewReport) {
      published.set(true);
    }
  }
}
