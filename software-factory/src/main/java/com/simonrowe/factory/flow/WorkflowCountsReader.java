package com.simonrowe.factory.flow;

import com.simonrowe.factory.flow.domain.NodeCounts;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * Counts a module's Temporal executions without reading any of them.
 *
 * <p>This is the whole reason the console needs no new persistence. Every module except the
 * activity-only {@code linear} sink runs as a workflow with a distinct type, so one visibility
 * query per status band serves all six — including {@code codereview}, which keeps no run
 * collection of its own and could not otherwise be counted at all.
 */
@Service
public class WorkflowCountsReader {

  /** The reporting window for settled runs. Well inside the namespace's 30-day retention. */
  private static final Duration WINDOW = Duration.ofHours(24);

  private final WorkflowClient client;

  public WorkflowCountsReader(final WorkflowClient client) {
    this.client = client;
  }

  /**
   * Counts one workflow type's executions.
   *
   * @param workflowType the Temporal workflow type, which for this repository is always the
   *     workflow interface's simple name
   * @return the counts, or null when Temporal could not be reached
   */
  public NodeCounts countsFor(final String workflowType) {
    String scope = "WorkflowType = '" + workflowType + "'";
    String since = " AND StartTime > '"
        + Instant.now().minus(WINDOW).truncatedTo(ChronoUnit.SECONDS) + "'";
    Long running = count(scope + " AND ExecutionStatus = 'Running'");
    Long ok = count(scope + " AND ExecutionStatus = 'Completed'" + since);
    Long failed = count(scope + " AND ExecutionStatus = 'Failed'" + since);
    if (running == null || ok == null || failed == null) {
      return null;
    }
    return new NodeCounts(running.intValue(), ok.intValue(), failed.intValue());
  }

  /**
   * The running query is deliberately unbounded by time: a deploy that started 26 hours ago and
   * has not finished is in flight now, and is precisely the run an operator opened this page for.
   */
  private Long count(final String query) {
    try {
      return client
          .getWorkflowServiceStubs()
          .blockingStub()
          .countWorkflowExecutions(
              CountWorkflowExecutionsRequest.newBuilder()
                  .setNamespace(client.getOptions().getNamespace())
                  .setQuery(query)
                  .build())
          .getCount();
    } catch (RuntimeException exception) {
      return null;
    }
  }
}
