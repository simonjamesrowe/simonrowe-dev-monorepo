package com.simonrowe.factory.flow;

import com.google.protobuf.Timestamp;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Lists a single node's recent work, for its drawer.
 *
 * <p>Unlike {@link WorkflowCountsReader}, which counts every module in one shot per status band,
 * this reads only the one node an operator actually opened — a drawer that listed every module's
 * runs would be worse than useless. An unknown node key, and a node with no {@link
 * NodeDescriptor#workflowType()} and no artifact reader either (only {@code production} today),
 * resolve to {@link FlowDetail#empty(String)} rather than an exception: a
 * drawer that throws takes the whole page down for a detail panel. Those genuinely mean "there is
 * nothing here" — a workflow type this service never queries.
 *
 * <p>A module's Temporal query failing is different again: it is answered with a {@link
 * FlowDetail} whose {@link FlowDetail#items()} is null, the same "could not read this" signal the
 * four artifact readers use, rather than collapsed to {@link FlowDetail#empty(String)} — a busy
 * module with an unreadable Temporal must never render as a quiet one.
 *
 * <p>The four artifact nodes with their own reader — {@code linear}, {@code pull-request},
 * {@code main} and {@code agent-setup} — carry the identical distinction one layer down: {@link
 * ArtifactCountsReader} returns null when its source could not be read, and that null is passed
 * straight through as {@link FlowDetail#items()} rather than collapsed to {@link
 * FlowDetail#empty(String)}. Losing that distinction would misreport a broken Linear or GitHub
 * read as a node with genuinely nothing open.
 */
@Service
public class FactoryFlowDetailService {

  private static final Logger LOG = LoggerFactory.getLogger(FactoryFlowDetailService.class);

  /** Ten is the whole drawer, not a page: an operator wants a glance, not a browsing history. */
  private static final int MAX_ITEMS = 10;

  /**
   * The artifact nodes {@link ArtifactCountsReader} can list. Every other node either has a
   * Temporal workflow type ({@link #descriptorFor(String)}) or neither — {@code production}, which
   * reports {@link FlowDetail#empty(String)}.
   */
  private static final Map<String, List<String>> EXTRA_LISTED_TYPES =
      Map.of("feedback", List.of("GuidanceClosedWorkflow"));

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
  private final TicketAgentStanding standing;

  /**
   * Creates a service backed by Temporal for modules and {@link ArtifactCountsReader} for
   * artifacts.
   *
   * @param client the Temporal client used to list a module's recent workflow executions
   * @param counts the reader for the four artifact nodes that carry their own list
   * @param standing which tickets the ticket agent holds, for the Linear node's items
   */
  public FactoryFlowDetailService(
      final WorkflowClient client,
      final ArtifactCountsReader counts,
      final TicketAgentStanding standing) {
    this.client = client;
    this.counts = counts;
    this.standing = standing;
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
                  // No ORDER BY: standard (SQL) visibility rejects the clause outright
                  // ("operation is not supported: 'ORDER BY' clause"), on both the local dev
                  // server and production's Postgres-backed visibility store. It is also
                  // unnecessary — standard visibility already returns executions newest-first by
                  // start time, verified empirically. Putting it back makes every drawer's run
                  // list fail silently again.
                  .setQuery(typeQuery(descriptor.get()))
                  .setPageSize(MAX_ITEMS)
                  .build())
          .getExecutionsList();
      return new FlowDetail(nodeKey, executions.stream()
          .map(execution -> item(descriptor.get(), execution))
          .collect(Collectors.toList()));
    } catch (RuntimeException exception) {
      // Null, not FlowDetail.empty(nodeKey): an unreadable Temporal is "could not read this",
      // never "nothing running" — the same distinction the artifact readers make one layer down.
      LOG.warn("Failed listing Temporal executions for factory flow node '{}'", nodeKey, exception);
      return new FlowDetail(nodeKey, null);
    }
  }

  /**
   * The visibility query for a node's runs. The feedback node also lists the guidance follow-ups,
   * because "did my guidance merge and close its ticket" is the question its drawer answers; its
   * counts stay the harvests alone.
   */
  static String typeQuery(final NodeDescriptor descriptor) {
    List<String> types = new ArrayList<>(List.of(descriptor.workflowType()));
    types.addAll(EXTRA_LISTED_TYPES.getOrDefault(descriptor.key(), List.of()));
    return types.stream()
        .map(type -> "WorkflowType = '" + type + "'")
        .collect(Collectors.joining(" OR "));
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
      case FactoryFlowTopology.LINEAR_NODE -> withStanding(counts.linearItems());
      case "pull-request" -> counts.pullRequestItems();
      case "main" -> counts.mainItems();
      default -> counts.agentSetupItems();
    };
    return Optional.of(new FlowDetail(nodeKey, items));
  }

  /** Marks each ticket the agent holds or handed back stuck; null stays null. */
  private List<FlowDetail.Item> withStanding(final List<FlowDetail.Item> items) {
    if (items == null) {
      return null;
    }
    Map<String, String> byIdentifier = standing.byIdentifier();
    return items.stream()
        .map(item -> byIdentifier.containsKey(item.id())
            ? item.withAgent(byIdentifier.get(item.id()))
            : item)
        .toList();
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
    String memoTitle = memo(execution, "title");
    if (memoTitle == null || memoTitle.isBlank()) {
      String title = descriptor.label() + " · " + TITLE_TIME.format(startTime);
      return new FlowDetail.Item(id, title, execution.getStatus().name(), startTime, null);
    }
    return memoItem(id, startTime, execution, memoTitle);
  }

  /**
   * A run that describes itself in its memo: the ticket agent upserts one at every phase, and
   * the guidance follow-up once, with what it did to the ticket.
   *
   * <p>Read from the memo so the listing alone describes every row. Querying each run instead
   * would replay every closed one on the Pi's worker, ten times a refresh.
   */
  private FlowDetail.Item memoItem(
      final String id,
      final Instant startTime,
      final WorkflowExecutionInfo execution,
      final String title) {
    String detail = memo(execution, "detail");
    String url = memo(execution, "url");
    String status = detail == null ? execution.getStatus().name() : detail;
    // A run that ended badly says so first; its last detail is what it was doing when it did.
    if (execution.getStatus() != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        && execution.getStatus() != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED) {
      status = execution.getStatus().name() + ": " + status;
    }
    // Only a live real run can be stopped: a dry run is over in one attempt, and its id says so.
    boolean stoppable =
        execution.getStatus() == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
            && memo(execution, "ticket") != null
            && !id.startsWith("ticket-agent-dry-run-");
    return new FlowDetail.Item(
        id, title, status, startTime, url == null || url.isBlank() ? null : url,
        stoppable ? FlowDetail.Item.RUNNING : null);
  }

  private String memo(final WorkflowExecutionInfo execution, final String field) {
    Payload payload = execution.getMemo().getFieldsMap().get(field);
    if (payload == null) {
      return null;
    }
    try {
      // Kept although the starter always validates its client options, which sets a converter:
      // options built without validation carry none, and a null here would blank every row.
      DataConverter converter = client.getOptions().getDataConverter();
      if (converter == null) {
        converter = GlobalDataConverter.get();
      }
      // Read as a string: every field the drawer shows is upserted as one, and asking the
      // converter for Object would hand back whatever its JSON mapper chose for the type.
      return converter.fromPayload(payload, String.class, String.class);
    } catch (RuntimeException exception) {
      LOG.debug("Unreadable memo field {} on {}", field, execution.getExecution().getWorkflowId());
      return null;
    }
  }

  private static Instant toInstant(final Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }
}
