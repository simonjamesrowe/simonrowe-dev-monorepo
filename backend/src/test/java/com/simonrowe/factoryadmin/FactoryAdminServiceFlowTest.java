package com.simonrowe.factoryadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
  void failsLoudlyWhenTheFactoryItselfCannotBeReached() {
    // Every node would be wrong, so a half-drawn graph is worse than an error the page can show.
    when(client.factoryFlow()).thenThrow(new RestClientException("connection refused"));

    assertThatThrownBy(() -> service().flow()).isInstanceOf(RuntimeException.class);
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
