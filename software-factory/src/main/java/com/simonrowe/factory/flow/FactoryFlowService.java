package com.simonrowe.factory.flow;

import com.simonrowe.factory.admin.FactoryStatusResponse;
import com.simonrowe.factory.admin.FactoryStatusService;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import com.simonrowe.factory.flow.domain.NodeHealth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Puts live figures onto the fixed topology.
 *
 * <p>Health is resolved in a fixed precedence — disabled, then unavailable, then degraded —
 * because those three send an operator to a flag, a container and a prerequisite respectively,
 * and a single collapsed "not working" sends them to the wrong one.
 */
@Service
public class FactoryFlowService {

  private final FactoryStatusService status;
  private final WorkflowCountsReader workflows;
  private final ArtifactCountsReader artifacts;

  /**
   * Creates a service over the three existing readers, adding no state of its own.
   *
   * @param status the module configuration and Temporal readiness reader
   * @param workflows the per-workflow-type Temporal execution counter
   * @param artifacts the Linear and GitHub artifact counter
   */
  public FactoryFlowService(
      final FactoryStatusService status,
      final WorkflowCountsReader workflows,
      final ArtifactCountsReader artifacts) {
    this.status = status;
    this.workflows = workflows;
    this.artifacts = artifacts;
  }

  /**
   * Builds the graph as this container sees it.
   *
   * @return every node with its counts and health, and every edge
   */
  public FactoryFlowResponse flow() {
    Map<String, FactoryStatusResponse.ModuleStatus> modules =
        status.status().modules().stream()
            .collect(Collectors.toMap(
                FactoryStatusResponse.ModuleStatus::key, Function.identity()));
    NodeCounts linear = artifacts.linearCounts();
    List<FlowNode> nodes = new ArrayList<>();
    for (NodeDescriptor descriptor : FactoryFlowTopology.NODES) {
      nodes.add(node(descriptor, modules, linear));
    }
    return new FactoryFlowResponse(Instant.now(), nodes, FactoryFlowTopology.EDGES);
  }

  private FlowNode node(
      final NodeDescriptor descriptor,
      final Map<String, FactoryStatusResponse.ModuleStatus> modules,
      final NodeCounts linear) {
    NodeCounts counts = countsFor(descriptor, linear);
    FactoryStatusResponse.ModuleStatus module =
        descriptor.moduleKey() == null ? null : modules.get(descriptor.moduleKey());
    NodeHealth health = health(descriptor, module, counts, linear);
    String diagnostic = module == null ? null : module.diagnostic();
    return new FlowNode(
        descriptor.key(), descriptor.kind(), descriptor.band(), descriptor.label(),
        counts, health, diagnostic);
  }

  private NodeCounts countsFor(final NodeDescriptor descriptor, final NodeCounts linear) {
    if (descriptor.workflowType() != null) {
      return workflows.countsFor(descriptor.workflowType());
    }
    return switch (descriptor.key()) {
      case FactoryFlowTopology.LINEAR_NODE -> linear;
      case "pull-request" -> artifacts.pullRequestCounts();
      case "main" -> artifacts.mainCounts();
      case "agent-setup" -> artifacts.agentSetupCounts();
      // The build agent runs on a machine this container cannot reach, and production's state is
      // already reported by the platform status endpoint the console renders separately.
      default -> NodeCounts.NONE;
    };
  }

  private NodeHealth health(
      final NodeDescriptor descriptor,
      final FactoryStatusResponse.ModuleStatus module,
      final NodeCounts counts,
      final NodeCounts linear) {
    if (FactoryFlowTopology.BUILD.equals(descriptor.key())) {
      // Derived entirely from Linear: work waiting with nothing running means nothing is
      // listening. Nothing waiting means nothing to do. They are different facts.
      if (linear == null) {
        return NodeHealth.UNAVAILABLE;
      }
      return linear.inFlight() > 0 ? NodeHealth.OFFLINE : NodeHealth.IDLE;
    }
    if (module != null && Boolean.FALSE.equals(module.configured())) {
      return NodeHealth.DISABLED;
    }
    if (counts == null || module != null && module.configured() == null) {
      return NodeHealth.UNAVAILABLE;
    }
    if (module != null && !module.ready()) {
      return NodeHealth.DEGRADED;
    }
    return NodeHealth.READY;
  }
}
