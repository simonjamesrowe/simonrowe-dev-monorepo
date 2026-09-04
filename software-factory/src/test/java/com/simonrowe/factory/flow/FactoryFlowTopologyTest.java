package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.flow.domain.Band;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import com.simonrowe.factory.flow.domain.NodeKind;
import java.util.List;
import java.util.Set;
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
