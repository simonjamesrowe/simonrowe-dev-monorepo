package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.admin.FactoryStatusResponse;
import com.simonrowe.factory.admin.FactoryStatusService;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.flow.domain.NodeHealth;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FactoryFlowServiceTest {

  private final FactoryStatusService status = mock(FactoryStatusService.class);
  private final WorkflowCountsReader workflows = mock(WorkflowCountsReader.class);
  private final ArtifactCountsReader artifacts = mock(ArtifactCountsReader.class);

  @Test
  void returnsEveryTopologyNodeAndEdge() {
    givenStatus();
    FactoryFlowResponse flow = service().flow();

    assertThat(flow.nodes()).hasSize(FactoryFlowTopology.NODES.size());
    assertThat(flow.edges()).isEqualTo(FactoryFlowTopology.EDGES);
  }

  @Test
  void reportsUnavailableWhenTemporalCouldNotBeCounted() {
    givenStatus();
    when(workflows.countsFor("LogWatchWorkflow")).thenReturn(null);

    assertThat(node(service().flow(), "logwatch").health()).isEqualTo(NodeHealth.UNAVAILABLE);
  }

  @Test
  void reportsDisabledAheadOfDegradedWhenTheModuleIsSwitchedOff() {
    // "Switched off" and "on but broken" send an operator to different places: one is a flag,
    // the other is a container. Collapsing them was the first cut of the status endpoint and it
    // sent people looking for an outage when the answer was configuration.
    givenStatus(module("logwatch", false, false, "Disabled by configuration"));
    when(workflows.countsFor("LogWatchWorkflow")).thenReturn(NodeCounts.NONE);

    assertThat(node(service().flow(), "logwatch").health()).isEqualTo(NodeHealth.DISABLED);
  }

  @Test
  void reportsDegradedWhenTheModuleIsEnabledButNotReady() {
    givenStatus(module("logwatch", true, false, "Required Temporal poller is missing"));
    when(workflows.countsFor("LogWatchWorkflow")).thenReturn(NodeCounts.NONE);

    FlowNode logwatch = node(service().flow(), "logwatch");
    assertThat(logwatch.health()).isEqualTo(NodeHealth.DEGRADED);
    assertThat(logwatch.diagnostic()).isEqualTo("Required Temporal poller is missing");
  }

  @Test
  void putsTheLinearModulesHealthOnTheLinearArtifactNode() {
    // The linear module is activity-only and is deliberately not drawn as a box.
    givenStatus(module("linear", true, false, "Enabled but not usable: LINEAR_API_KEY is unset"));
    when(artifacts.linearCounts()).thenReturn(new NodeCounts(4, 1, 0));

    FlowNode linear = node(service().flow(), "linear");
    assertThat(linear.health()).isEqualTo(NodeHealth.DEGRADED);
    assertThat(linear.counts()).isEqualTo(new NodeCounts(4, 1, 0));
  }

  @Test
  void reportsTheBuildNodeAsOfflineWhenWorkIsWaitingAndNothingHasRun() {
    // The build agent lives on a laptop the Pi cannot reach. Empty is not the same as offline:
    // work waiting with nothing moving is OFFLINE, nothing waiting is IDLE.
    givenStatus();
    when(artifacts.linearCounts()).thenReturn(new NodeCounts(3, 0, 0));

    FlowNode build = node(service().flow(), "build");
    assertThat(build.health()).isEqualTo(NodeHealth.OFFLINE);
    // The waiting count must appear on the badge that says OFFLINE, not read as zero beside it.
    assertThat(build.counts()).isEqualTo(new NodeCounts(3, 0, 0));
  }

  @Test
  void reportsTheBuildNodeAsIdleWhenThereIsNothingWaiting() {
    givenStatus();
    when(artifacts.linearCounts()).thenReturn(NodeCounts.NONE);

    FlowNode build = node(service().flow(), "build");
    assertThat(build.health()).isEqualTo(NodeHealth.IDLE);
    assertThat(build.counts()).isEqualTo(NodeCounts.NONE);
  }

  @Test
  void reportsTheBuildNodeAsUnavailableWithNullCountsWhenLinearCannotBeRead() {
    // A null must never render as a zero: an unreadable Linear is UNAVAILABLE, and the counts
    // that would otherwise sit next to that badge must say "unknown", not "nothing waiting".
    givenStatus();
    when(artifacts.linearCounts()).thenReturn(null);

    FlowNode build = node(service().flow(), "build");
    assertThat(build.health()).isEqualTo(NodeHealth.UNAVAILABLE);
    assertThat(build.counts()).isNull();
  }

  @Test
  void reportsProductionAsNotTrackedRatherThanReady() {
    // production has no owning module and no artifact source this container can read. Reporting
    // it unconditionally READY would be a permanently green badge with nothing behind it.
    givenStatus();

    FlowNode production = node(service().flow(), "production");
    assertThat(production.health()).isEqualTo(NodeHealth.NOT_TRACKED);
    assertThat(production.counts()).isNull();
  }

  private FactoryFlowService service() {
    return new FactoryFlowService(status, workflows, artifacts);
  }

  private void givenStatus(final FactoryStatusResponse.ModuleStatus... overrides) {
    List<FactoryStatusResponse.ModuleStatus> modules = new java.util.ArrayList<>(List.of(
        module("codereview", true, true, null),
        module("feedback", true, true, null),
        module("cvefix", true, true, null),
        module("deploy", true, true, null),
        module("linear", true, true, null),
        module("platformbackup", true, true, null),
        module("logwatch", true, true, null)));
    for (FactoryStatusResponse.ModuleStatus override : overrides) {
      modules.removeIf(existing -> existing.key().equals(override.key()));
      modules.add(override);
    }
    when(status.status())
        .thenReturn(new FactoryStatusResponse("software-factory", Instant.now(), modules));
    when(workflows.countsFor(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(NodeCounts.NONE);
    when(artifacts.linearCounts()).thenReturn(NodeCounts.NONE);
  }

  private static FactoryStatusResponse.ModuleStatus module(
      final String key, final boolean configured, final boolean ready, final String diagnostic) {
    return new FactoryStatusResponse.ModuleStatus(
        key, key, configured, key, 1, 1, "trigger", null, List.of(), ready, diagnostic);
  }

  private static FlowNode node(final FactoryFlowResponse flow, final String key) {
    return flow.nodes().stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No node " + key));
  }
}
