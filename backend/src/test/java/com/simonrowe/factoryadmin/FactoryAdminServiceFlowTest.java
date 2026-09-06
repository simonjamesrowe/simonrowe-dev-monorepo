package com.simonrowe.factoryadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/**
 * The flow graph is assembled from two containers, exactly like {@link FactoryAdminStatus}: deploy
 * and platform backup are owned by the deployer, never by software-factory's own switched-off view
 * of them.
 */
class FactoryAdminServiceFlowTest {

  private final FactoryAdminClient client = mock(FactoryAdminClient.class);

  @Test
  void takesDeployerOwnedNodesFromTheDeployer() {
    // deploy and platformbackup run on the deployer. software-factory's own view of them is the
    // switched-off one, so forwarding it would report a working deploy as disabled.
    when(client.factoryFlow())
        .thenReturn(flow(node("deploy", "DISABLED"), node("logwatch", "READY")));
    when(client.deployerFlow())
        .thenReturn(flow(node("deploy", "READY"), node("logwatch", "DISABLED")));

    FactoryFlow merged = service().flow();

    assertThat(node(merged, "deploy").health()).isEqualTo("READY");
    assertThat(node(merged, "logwatch").health()).isEqualTo("READY");
  }

  @Test
  void reportsDeployerOwnedNodesAsUnavailableWhenTheDeployerCannotBeReached() {
    when(client.factoryFlow())
        .thenReturn(flow(node("deploy", "DISABLED"), node("logwatch", "READY")));
    when(client.deployerFlow()).thenThrow(new RestClientException("connection refused"));

    FactoryFlow merged = service().flow();

    assertThat(node(merged, "deploy").health()).isEqualTo("UNAVAILABLE");
    assertThat(node(merged, "logwatch").health()).isEqualTo("READY");
  }

  @Test
  void returnsAnUnavailableButCompleteGraphWhenTheFactoryCannotBeReached() {
    // The one page whose purpose is showing factory health must not go blank at exactly the
    // moment the factory is down. The deployer runs the identical image, so its own flow
    // response carries the same topology shape (key, kind, band, label, edges) - this is what
    // lets every node still be drawn, just uniformly marked UNAVAILABLE rather than trusted.
    when(client.factoryFlow()).thenThrow(new RestClientException("connection refused"));
    when(client.deployerFlow())
        .thenReturn(flow(node("deploy", "READY"), node("logwatch", "READY")));

    FactoryFlow result = service().flow();

    assertThat(result.nodes()).extracting(FactoryFlow.Node::key)
        .containsExactlyInAnyOrder("deploy", "logwatch");
    assertThat(result.nodes()).allSatisfy(candidate -> {
      assertThat(candidate.health()).isEqualTo("UNAVAILABLE");
      assertThat(candidate.counts()).isNull();
      assertThat(candidate.diagnostic()).isEqualTo("Software Factory could not be reached");
    });
  }

  @Test
  void returnsAnEmptyGraphWhenNeitherContainerCanBeReached() {
    // No topology source is reachable anywhere: the console can only say "nothing to draw", never
    // a 500, and this must not fabricate a hardcoded second copy of the topology to fill the gap.
    when(client.factoryFlow()).thenThrow(new RestClientException("connection refused"));
    when(client.deployerFlow()).thenThrow(new RestClientException("connection refused"));

    FactoryFlow result = service().flow();

    assertThat(result.nodes()).isEmpty();
    assertThat(result.edges()).isEmpty();
  }

  private FactoryAdminService service() {
    return new FactoryAdminService(client, mock(FactoryAdminProperties.class), null);
  }

  private static FactoryFlow flow(final FactoryFlow.Node... nodes) {
    return new FactoryFlow(Instant.now(), List.of(nodes), List.of());
  }

  private static FactoryFlow.Node node(final String key, final String health) {
    return new FactoryFlow.Node(key, "MODULE", "SHIP", key, null, health, null);
  }

  private static FactoryFlow.Node node(final FactoryFlow flow, final String key) {
    return flow.nodes().stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No node " + key));
  }
}
