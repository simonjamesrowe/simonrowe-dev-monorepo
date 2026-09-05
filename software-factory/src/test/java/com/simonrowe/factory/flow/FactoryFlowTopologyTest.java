package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.admin.ModulePrerequisites;
import com.simonrowe.factory.codereview.workflow.CodeReviewWorkflow;
import com.simonrowe.factory.cvefix.workflow.CveFixWorkflow;
import com.simonrowe.factory.deploy.workflow.DeployWorkflow;
import com.simonrowe.factory.feedback.workflow.ReviewFeedbackWorkflow;
import com.simonrowe.factory.flow.domain.Band;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import com.simonrowe.factory.flow.domain.NodeKind;
import com.simonrowe.factory.logwatch.workflow.LogWatchWorkflow;
import com.simonrowe.factory.platformbackup.workflow.PlatformBackupWorkflow;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FactoryFlowTopologyTest {

  @Test
  void pinsTheTwelveNodes() {
    assertThat(FactoryFlowTopology.NODES).extracting(NodeDescriptor::key)
        .containsExactlyInAnyOrder(
            "logwatch", "cvefix", "linear", "build", "pull-request", "codereview",
            "main", "deploy", "production", "feedback", "agent-setup", "platformbackup");
  }

  @Test
  void doesNotDrawTheLinearModuleAsItsOwnNode() {
    // The linear module is the factory's only activity-only task queue: nothing flows THROUGH it,
    // so it is a badge on the Linear artifact node rather than a box of its own.
    NodeDescriptor linear = node("linear");
    assertThat(linear.kind()).isEqualTo(NodeKind.ARTIFACT);
    assertThat(linear.moduleKey()).isEqualTo("linear");
  }

  @Test
  void moduleKeysOnNodesMatchModulePrerequisitesExactly() {
    // The guarantee the spec names: adding an eighth module without drawing it must fail the
    // build, and a typo in a node's moduleKey must be equally loud, since it would silently
    // render that node's health as permanently unknown rather than throwing anywhere.
    Set<String> drawnModuleKeys = FactoryFlowTopology.NODES.stream()
        .map(NodeDescriptor::moduleKey)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Set<String> authoritativeKeys = Set.copyOf(ModulePrerequisites.KEYS);

    Set<String> undrawn = new TreeSet<>(authoritativeKeys);
    undrawn.removeAll(drawnModuleKeys);
    Set<String> unknown = new TreeSet<>(drawnModuleKeys);
    unknown.removeAll(authoritativeKeys);

    assertThat(undrawn)
        .as("module key(s) %s are in ModulePrerequisites.KEYS but no node in "
            + "FactoryFlowTopology.NODES carries them as a moduleKey - a module was added "
            + "without being drawn into the graph", undrawn)
        .isEmpty();
    assertThat(unknown)
        .as("node moduleKey(s) %s do not match any key in ModulePrerequisites.KEYS - likely "
            + "a typo, which would leave that node's health permanently unknown", unknown)
        .isEmpty();
  }

  @Test
  void workflowTypesOnNodesMatchTheRealWorkflowInterfacesExactly() {
    // NodeDescriptor.workflowType() is a hand-typed literal, checked against no @WorkflowInterface
    // anywhere else. A rename leaves this string stale: WorkflowCountsReader's visibility query
    // then matches nothing, Temporal answers count: 0 with no exception, and the node reports
    // READY with all-zero counts forever while the module keeps running - silent success with a
    // false answer, worse than every other unreadable-source path here, which throws. Referencing
    // the interfaces directly (not their names as further literals) means a rename breaks
    // compilation instead of merely failing this assertion.
    Set<String> expected = Set.of(
        CodeReviewWorkflow.class.getSimpleName(),
        ReviewFeedbackWorkflow.class.getSimpleName(),
        CveFixWorkflow.class.getSimpleName(),
        DeployWorkflow.class.getSimpleName(),
        PlatformBackupWorkflow.class.getSimpleName(),
        LogWatchWorkflow.class.getSimpleName());
    Set<String> drawnWorkflowTypes = FactoryFlowTopology.NODES.stream()
        .map(NodeDescriptor::workflowType)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());

    Set<String> undrawn = new TreeSet<>(expected);
    undrawn.removeAll(drawnWorkflowTypes);
    Set<String> unknown = new TreeSet<>(drawnWorkflowTypes);
    unknown.removeAll(expected);

    assertThat(undrawn)
        .as("workflow interface simple name(s) %s have no node in FactoryFlowTopology.NODES "
            + "carrying them as workflowType", undrawn)
        .isEmpty();
    assertThat(unknown)
        .as("node workflowType(s) %s do not match any real @WorkflowInterface simple name - "
            + "likely a stale rename, which would leave that node's Temporal query matching "
            + "nothing and READY with all-zero counts forever", unknown)
        .isEmpty();
  }

  @Test
  void leavesPlatformBackupOffTheRing() {
    // It participates in no loop. Drawing it on one would be a lie.
    assertThat(FactoryFlowTopology.EDGES)
        .noneMatch(edge -> edge.from().equals("platformbackup")
            || edge.to().equals("platformbackup"));
  }

  @Test
  void givesEveryNodeAnEdgeExceptPlatformBackup() {
    Set<String> connected = FactoryFlowTopology.EDGES.stream()
        .flatMap(edge -> java.util.stream.Stream.of(edge.from(), edge.to()))
        .collect(Collectors.toSet());
    List<String> orphans = FactoryFlowTopology.NODES.stream()
        .map(NodeDescriptor::key)
        .filter(key -> !connected.contains(key))
        .toList();
    assertThat(orphans).containsExactly("platformbackup");
  }

  @Test
  void wiresEveryEdgeToNodesThatExist() {
    Set<String> keys = FactoryFlowTopology.NODES.stream()
        .map(NodeDescriptor::key).collect(Collectors.toSet());
    assertThat(FactoryFlowTopology.EDGES)
        .allSatisfy(edge -> {
          assertThat(keys).contains(edge.from());
          assertThat(keys).contains(edge.to());
        });
  }

  @Test
  void drawsCvefixAsDownstreamOfMergeNotAsSource() {
    // Publish uploads image and manifest SBOMs to Dependency-Track, which cvefix reads. Without
    // this edge cvefix is a source with no input, which is simply wrong.
    assertThat(FactoryFlowTopology.EDGES)
        .anyMatch(edge -> edge.from().equals("main") && edge.to().equals("cvefix"));
  }

  @Test
  void closesTheMainLoop() {
    List<String> ring = List.of(
        "linear", "build", "pull-request", "main", "deploy", "production", "logwatch", "linear");
    for (int i = 0; i < ring.size() - 1; i++) {
      String from = ring.get(i);
      String to = ring.get(i + 1);
      assertThat(FactoryFlowTopology.EDGES)
          .as("edge %s -> %s", from, to)
          .anyMatch(edge -> edge.from().equals(from) && edge.to().equals(to));
    }
  }

  @Test
  void assignsBandToEveryNode() {
    assertThat(FactoryFlowTopology.NODES).allSatisfy(node -> assertThat(node.band()).isNotNull());
    assertThat(FactoryFlowTopology.NODES).filteredOn(n -> n.band() == Band.UTILITY)
        .extracting(NodeDescriptor::key).containsExactly("platformbackup");
  }

  private static NodeDescriptor node(final String key) {
    return FactoryFlowTopology.NODES.stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No node " + key));
  }
}
