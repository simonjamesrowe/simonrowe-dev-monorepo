package com.simonrowe.factory.feedback.workflow;

import com.simonrowe.factory.feedback.config.FeedbackTaskQueues;
import com.simonrowe.factory.feedback.domain.GuidanceClosedRequest;
import com.simonrowe.factory.feedback.domain.GuidanceClosedResult;
import com.simonrowe.factory.git.PullRequestUrl;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.LinkedIssue;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic: read the ticket's attachments, ask GitHub which of its pull requests merged, and
 * close it only when all of them have.
 *
 * <p>The ticket's own attachments are the list of what has to merge, rather than the feedback
 * workflow's Mongo record, because Linear is where a person sees the ticket. If someone detaches a
 * guidance pull request they have decided not to merge, the ticket closes when the rest do.
 */
@WorkflowImpl(taskQueues = FeedbackTaskQueues.REVIEW_FEEDBACK)
public class GuidanceClosedWorkflowImpl implements GuidanceClosedWorkflow {

  private static final RetryOptions NETWORK_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(10))
          .setMaximumAttempts(3)
          .build();

  private final FeedbackActivities feedback =
      Workflow.newActivityStub(
          FeedbackActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  private final LinearActivities linear =
      Workflow.newActivityStub(
          LinearActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(LinearTaskQueues.LINEAR)
              .setStartToCloseTimeout(Duration.ofSeconds(90))
              .setScheduleToCloseTimeout(Duration.ofMinutes(5))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  @Override
  public GuidanceClosedResult close(final GuidanceClosedRequest request) {
    List<String> completed = new ArrayList<>();
    List<String> waiting = new ArrayList<>();
    for (LinkedIssue issue : linear.issuesLinkedTo(request.pullRequestUrl())) {
      if (!issue.stateType().open()) {
        continue;
      }
      if (!request.merged()) {
        linear.commentOn(
            issue.id(),
            "Guidance pull request %s closed without merging, so this ticket stays open."
                .formatted(request.pullRequestUrl()));
        waiting.add(issue.identifier());
        continue;
      }
      List<String> others = otherPullRequests(issue, request.pullRequestUrl());
      List<String> unmerged = others.isEmpty() ? List.of() : feedback.unmergedPullRequests(others);
      if (unmerged.isEmpty()) {
        linear.complete(
            issue.id(),
            "Every guidance pull request for this ticket has merged, most recently %s."
                .formatted(request.pullRequestUrl()));
        completed.add(issue.identifier());
      } else {
        linear.commentOn(
            issue.id(),
            "Guidance pull request %s merged. Still waiting on %s."
                .formatted(request.pullRequestUrl(), String.join(", ", unmerged)));
        waiting.add(issue.identifier());
      }
    }
    Workflow.upsertMemo(memo(request, completed, waiting));
    return new GuidanceClosedResult(completed, waiting);
  }

  /**
   * What this run did, for the console's Feedback drawer, which lists these runs from Temporal's
   * visibility store and reads each one's memo rather than querying it.
   */
  static Map<String, Object> memo(
      final GuidanceClosedRequest request,
      final List<String> completed,
      final List<String> waiting) {
    String detail;
    if (!completed.isEmpty()) {
      detail = "Merged; " + String.join(", ", completed) + " done";
    } else if (!waiting.isEmpty()) {
      detail = (request.merged() ? "Merged; " : "Closed unmerged; ")
          + String.join(", ", waiting) + " still open";
    } else {
      detail = request.merged() ? "Merged; no open ticket" : "Closed unmerged; no open ticket";
    }
    Map<String, Object> memo = new LinkedHashMap<>();
    memo.put("title", "Guidance %s/%s#%d"
        .formatted(request.owner(), request.repository(), request.pullNumber()));
    memo.put("detail", detail);
    memo.put("url", request.pullRequestUrl());
    return memo;
  }

  /** The issue's other GitHub pull request attachments, the one that just closed excluded. */
  static List<String> otherPullRequests(final LinkedIssue issue, final String closedUrl) {
    Optional<String> closed = PullRequestUrl.parse(closedUrl).map(PullRequestUrl::canonical);
    List<String> others = new ArrayList<>();
    for (String attached : issue.attachmentUrls()) {
      PullRequestUrl.parse(attached)
          .map(PullRequestUrl::canonical)
          .filter(url -> closed.map(value -> !value.equalsIgnoreCase(url)).orElse(true))
          .filter(url -> others.stream().noneMatch(url::equalsIgnoreCase))
          .ifPresent(others::add);
    }
    return others;
  }
}
