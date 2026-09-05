package com.simonrowe.factory.flow;

import com.google.protobuf.Timestamp;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.client.WorkflowClient;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

  /**
   * Renders a run's start time for the title. UTC, not the server's zone: this JVM's host zone
   * is an operational detail, not a fact about the run, and Temporal itself always reports in
   * UTC.
   */
  private static final DateTimeFormatter TITLE_TIME =
      DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);

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
    Optional<NodeDescriptor> descriptor = descriptorFor(nodeKey);
    if (descriptor.isEmpty()) {
      return FlowDetail.empty(nodeKey);
    }
    try {
      List<WorkflowExecutionInfo> executions = client
          .getWorkflowServiceStubs()
          .blockingStub()
          .listWorkflowExecutions(
              ListWorkflowExecutionsRequest.newBuilder()
                  .setNamespace(client.getOptions().getNamespace())
                  .setQuery("WorkflowType = '" + descriptor.get().workflowType()
                      + "' ORDER BY StartTime DESC")
                  .setPageSize(MAX_ITEMS)
                  .build())
          .getExecutionsList();
      return new FlowDetail(nodeKey, executions.stream()
          .map(execution -> item(descriptor.get(), execution))
          .collect(Collectors.toList()));
    } catch (RuntimeException exception) {
      return FlowDetail.empty(nodeKey);
    }
  }

  /**
   * Finds the node's descriptor, only when it has a workflow type to query.
   *
   * <p>An unknown key and a known artifact node with no {@code workflowType} are handled
   * identically here on purpose: both mean there is no Temporal query this method can run.
   */
  private static Optional<NodeDescriptor> descriptorFor(final String nodeKey) {
    return FactoryFlowTopology.NODES.stream()
        .filter(candidate -> candidate.key().equals(nodeKey))
        .filter(candidate -> candidate.workflowType() != null)
        .findFirst();
  }

  /**
   * Builds a human-readable item, rather than repeating the raw workflow id as the title: two
   * runs of the same module are otherwise distinguishable only by comparing hashes. The id
   * itself is still carried as {@link FlowDetail.Item#id()}, so nothing is lost — the title is
   * the node's label plus the start time, which is what actually tells two rows apart.
   */
  private FlowDetail.Item item(
      final NodeDescriptor descriptor, final WorkflowExecutionInfo execution) {
    String id = execution.getExecution().getWorkflowId();
    Instant startTime = toInstant(execution.getStartTime());
    String title = descriptor.label() + " · " + TITLE_TIME.format(startTime);
    return new FlowDetail.Item(id, title, execution.getStatus().name(), startTime, null);
  }

  private static Instant toInstant(final Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }
}
