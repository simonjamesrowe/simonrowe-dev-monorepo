package com.simonrowe.factory.flow;

import com.google.protobuf.Timestamp;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.client.WorkflowClient;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Lists a single node's recent Temporal runs, for its drawer.
 *
 * <p>Unlike {@link WorkflowCountsReader}, which counts every module in one shot per status band,
 * this reads only the one node an operator actually opened — a drawer that listed every module's
 * runs would be worse than useless. Every failure, including an unknown node key or a node with
 * no {@link NodeDescriptor#workflowType()} at all (an artifact node, or one this container does
 * not own), resolves to {@link FlowDetail#empty(String)} rather than an exception: a drawer that
 * throws takes the whole page down for a detail panel.
 */
@Service
public class FactoryFlowDetailService {

  /** Ten is the whole drawer, not a page: an operator wants a glance, not a browsing history. */
  private static final int MAX_ITEMS = 10;

  private final WorkflowClient client;

  public FactoryFlowDetailService(final WorkflowClient client) {
    this.client = client;
  }

  /**
   * Lists one node's recent runs.
   *
   * @param nodeKey the node whose drawer is open
   * @return that node's items, newest first, or empty when there is nothing to show or the
   *     source could not be read
   */
  public FlowDetail detail(final String nodeKey) {
    String workflowType = workflowTypeFor(nodeKey);
    if (workflowType == null) {
      return FlowDetail.empty(nodeKey);
    }
    try {
      List<WorkflowExecutionInfo> executions = client
          .getWorkflowServiceStubs()
          .blockingStub()
          .listWorkflowExecutions(
              ListWorkflowExecutionsRequest.newBuilder()
                  .setNamespace(client.getOptions().getNamespace())
                  .setQuery("WorkflowType = '" + workflowType + "' ORDER BY StartTime DESC")
                  .setPageSize(MAX_ITEMS)
                  .build())
          .getExecutionsList();
      return new FlowDetail(nodeKey, executions.stream().map(this::item).collect(
          Collectors.toList()));
    } catch (RuntimeException exception) {
      return FlowDetail.empty(nodeKey);
    }
  }

  private static String workflowTypeFor(final String nodeKey) {
    return FactoryFlowTopology.NODES.stream()
        .filter(descriptor -> descriptor.key().equals(nodeKey))
        .findFirst()
        .map(NodeDescriptor::workflowType)
        .orElse(null);
  }

  private FlowDetail.Item item(final WorkflowExecutionInfo execution) {
    String id = execution.getExecution().getWorkflowId();
    return new FlowDetail.Item(
        id, id, execution.getStatus().name(), toInstant(execution.getStartTime()), null);
  }

  private static Instant toInstant(final Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }
}
