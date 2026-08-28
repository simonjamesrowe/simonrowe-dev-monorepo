package com.simonrowe.factory.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Follows any factory run, whichever module started it.
 *
 * <p>There is deliberately one implementation rather than six. Every factory workflow exposes its
 * state through a query method named {@code progress} returning an object with {@code phase} and
 * {@code detail} fields, so an untyped query serves all of them and a new module gets progress
 * reporting for free. The alternative — a typed stub per module — needs the caller to already know
 * which module owns a workflow id, which the console does not reliably know after a page reload.
 */
@Service
public class FactoryRunStatusService {

  /**
   * Every status Temporal considers closed. {@code CONTINUED_AS_NEW} is closed for this run even
   * though the logical operation carries on, which no factory workflow currently does.
   */
  private static final Set<WorkflowExecutionStatus> CLOSED =
      Set.of(
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW);

  private static final String QUERY = "progress";

  private final WorkflowClient client;

  public FactoryRunStatusService(final WorkflowClient client) {
    this.client = client;
  }

  /**
   * Reports where a run has got to.
   *
   * @param workflowId the workflow identity returned when the run was accepted
   * @return the normalised progress
   * @throws ResponseStatusException 404 when Temporal has no such workflow
   */
  public FactoryRunProgress progress(final String workflowId) {
    DescribeWorkflowExecutionResponse described = describe(workflowId);
    if (described == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such factory run");
    }
    WorkflowExecutionStatus status = described.getWorkflowExecutionInfo().getStatus();
    JsonNode progress = query(workflowId);
    return new FactoryRunProgress(
        workflowId,
        described.getWorkflowExecutionInfo().getExecution().getRunId(),
        status.name(),
        text(progress, "phase"),
        text(progress, "detail"),
        CLOSED.contains(status));
  }

  private DescribeWorkflowExecutionResponse describe(final String workflowId) {
    try {
      return client
          .getWorkflowServiceStubs()
          .blockingStub()
          .describeWorkflowExecution(
              DescribeWorkflowExecutionRequest.newBuilder()
                  .setNamespace(client.getOptions().getNamespace())
                  .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                  .build());
    } catch (RuntimeException exception) {
      return null;
    }
  }

  /**
   * A failed or terminated workflow cannot answer a query, and a running one occasionally cannot
   * either. That is a partial answer, not an error: the execution status alone is still useful, so
   * this returns null and lets the caller report what it does know.
   */
  private JsonNode query(final String workflowId) {
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
      return stub.query(QUERY, JsonNode.class);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private static String text(final JsonNode node, final String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    return node.get(field).asText();
  }
}
