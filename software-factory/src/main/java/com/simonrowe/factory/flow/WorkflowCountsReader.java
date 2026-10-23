package com.simonrowe.factory.flow;

import com.simonrowe.factory.flow.domain.NodeCounts;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

  /**
   * Retries of a throttled count. One page load asks three counts per workflow node, about two
   * dozen in a burst, and Temporal's default visibility limit is 10 a second per namespace
   * ({@code frontend.namespaceRPS.visibility}). When #278 put an eighth workflow node ahead of the
   * ticket agent, its three counts became the tenth to twelfth and were refused, and the console
   * showed the agent as unknown on every load. Waiting for the bucket to refill is enough.
   */
  static final int THROTTLE_RETRIES = 3;

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowCountsReader.class);

  private final WorkflowClient client;
  private final Sleeper sleeper;

  /** Pauses between throttled attempts; a seam so tests do not sleep. */
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  @Autowired
  public WorkflowCountsReader(final WorkflowClient client) {
    this(client, duration -> Thread.sleep(duration.toMillis()));
  }

  WorkflowCountsReader(final WorkflowClient client, final Sleeper sleeper) {
    this.client = client;
    this.sleeper = sleeper;
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
    String since = " AND StartTime > '%s'"
        .formatted(Instant.now().minus(WINDOW).truncatedTo(ChronoUnit.SECONDS));
    // Stop at the first unreadable count: the node is unknown either way, and asking the next
    // two would only spend more of the retry budget against a throttled or absent Temporal.
    Long running = count(scope + " AND ExecutionStatus = 'Running'");
    if (running == null) {
      return null;
    }
    Long ok = count(scope + " AND ExecutionStatus = 'Completed'" + since);
    if (ok == null) {
      return null;
    }
    Long failed = count(scope + " AND ExecutionStatus = 'Failed'" + since);
    if (failed == null) {
      return null;
    }
    return new NodeCounts(running.intValue(), ok.intValue(), failed.intValue());
  }

  /**
   * The running query is deliberately unbounded by time: a deploy that started 26 hours ago and
   * has not finished is in flight now, and is precisely the run an operator opened this page for.
   */
  private Long count(final String query) {
    for (int attempt = 0; ; attempt++) {
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
      } catch (StatusRuntimeException exception) {
        if (exception.getStatus().getCode() != Status.Code.RESOURCE_EXHAUSTED
            || attempt >= THROTTLE_RETRIES) {
          return unread(query, exception);
        }
        if (!pause(attempt)) {
          return unread(query, exception);
        }
      } catch (RuntimeException exception) {
        return unread(query, exception);
      }
    }
  }

  /** Backs off 300ms, 600ms, 900ms: a 10-a-second bucket refills within the first. */
  private boolean pause(final int attempt) {
    try {
      sleeper.sleep(Duration.ofMillis(300L * (attempt + 1)));
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Null, which the console shows as unknown, but logged so an unknown has a findable cause. */
  private Long unread(final String query, final RuntimeException exception) {
    LOG.warn("Could not count workflows for [{}]: {}", query, exception.getMessage());
    return null;
  }
}
