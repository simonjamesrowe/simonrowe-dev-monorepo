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
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Lists a single node's recent work, for its drawer.
 *
 * <p>Unlike {@link WorkflowCountsReader}, which counts every module in one shot per status band,
 * this reads only the one node an operator actually opened — a drawer that listed every module's
 * runs would be worse than useless. A module's Temporal query failing, an unknown node key, and a
 * node with no {@link NodeDescriptor#workflowType()} and no artifact reader either (only {@code
 * production} and {@code build} today) all resolve to {@link FlowDetail#empty(String)} rather
 * than an exception: a drawer that throws takes the whole page down for a detail panel.
 *
 * <p>The four artifact nodes with their own reader — {@code linear}, {@code pull-request},
 * {@code main} and {@code agent-setup} — are different: {@link ArtifactCountsReader} returns null
 * when its source could not be read, and that null is passed straight through as {@link
 * FlowDetail#items()} rather than collapsed to {@link FlowDetail#empty(String)}. Losing that
 * distinction would misreport a broken Linear or GitHub read as a node with genuinely nothing
 * open.
 */
@Service
public class FactoryFlowDetailService {

  /** Ten is the whole drawer, not a page: an operator wants a glance, not a browsing history. */
  private static final int MAX_ITEMS = 10;

  /**
   * The artifact nodes {@link ArtifactCountsReader} can list. Every other node either has a
   * Temporal workflow type ({@link #descriptorFor(String)}) or neither — {@code production} and
   * {@code build}, which report {@link FlowDetail#empty(String)}.
   */
  private static final Set<String> ARTIFACT_NODES_WITH_READERS =
      Set.of(FactoryFlowTopology.LINEAR_NODE, "pull-request", "main", "agent-setup");

  /**
   * Renders a run's start time for the title. UTC, not the server's zone: this JVM's host zone
   * is an operational detail, not a fact about the run, and Temporal itself always reports in
   * UTC.
   */
  private static final DateTimeFormatter TITLE_TIME =
      DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);

  private final WorkflowClient client;
  private final ArtifactCountsReader counts;

  /**
   * Creates a service backed by Temporal for modules and {@link ArtifactCountsReader} for
   * artifacts.
   *
   * @param client the Temporal client used to list a module's recent workflow executions
   * @param counts the reader for the four artifact nodes that carry their own list
   */
  public FactoryFlowDetailService(final WorkflowClient client, final ArtifactCountsReader counts) {
    this.client = client;
    this.counts = counts;
  }

  /**
   * Lists one node's recent work.
   *
   * @param nodeKey the node whose drawer is open
   * @return that node's items, newest first; null items when an artifact node's own reader could
   *     not be read; empty when there is genuinely nothing to show
   */
  public FlowDetail detail(final String nodeKey) {
    Optional<FlowDetail> artifact = artifactDetail(nodeKey);
    if (artifact.isPresent()) {
      return artifact.get();
    }
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
   * Routes an artifact node to {@link ArtifactCountsReader}, before the Temporal-backed module
   * path below is even considered.
   *
   * @param nodeKey the node whose drawer is open
   * @return the artifact's detail when this node has a reader; empty when it does not, so the
   *     caller falls through to the module path or {@link FlowDetail#empty(String)}
   */
  private Optional<FlowDetail> artifactDetail(final String nodeKey) {
    if (!ARTIFACT_NODES_WITH_READERS.contains(nodeKey)) {
      return Optional.empty();
    }
    List<FlowDetail.Item> items = switch (nodeKey) {
      case FactoryFlowTopology.LINEAR_NODE -> counts.linearItems();
      case "pull-request" -> counts.pullRequestItems();
      case "main" -> counts.mainItems();
      default -> counts.agentSetupItems();
    };
    return Optional.of(new FlowDetail(nodeKey, items));
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
