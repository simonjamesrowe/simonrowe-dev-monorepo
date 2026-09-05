package com.simonrowe.factoryadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/**
 * {@code deploy} and {@code platformbackup} are deployer-owned, exactly as in {@link
 * FactoryAdminServiceFlowTest}. The deployer holds a read-only {@code FACTORY_READ_TOKEN} — never
 * the trigger token — so it is asked directly for these two keys' detail rather than the request
 * being short-circuited: unlike the trigger-token-gated actions, this one call the deployer can
 * genuinely answer. Every failure still collapses to an empty detail, never an exception — a
 * drawer that throws takes the whole page down for a detail panel.
 */
class FactoryAdminServiceFlowDetailTest {

  private final FactoryAdminClient client = mock(FactoryAdminClient.class);

  @Test
  void readsFlowDetailForTheFactoryOwnedNode() {
    when(client.factoryFlowDetail("logwatch")).thenReturn(
        new FactoryFlowDetail("logwatch", List.of(
            new FactoryFlowDetail.Item("logwatch-1", "logwatch-1", "COMPLETED", null, null))));

    FactoryFlowDetail detail = service().flowDetail("logwatch");

    assertThat(detail.items()).extracting(FactoryFlowDetail.Item::id)
        .containsExactly("logwatch-1");
    verify(client, never()).deployerFlowDetail("logwatch");
  }

  @Test
  void asksTheDeployerForTheDeployerOwnedNodesDetail() {
    // Not software-factory's own switched-off view of them, and not short-circuited to empty
    // either: the deployer holds the read token and can answer this one call truthfully.
    when(client.deployerFlowDetail("deploy")).thenReturn(
        new FactoryFlowDetail("deploy", List.of(
            new FactoryFlowDetail.Item("deploy-prod", "deploy-prod", "COMPLETED", null, null))));
    when(client.deployerFlowDetail("platformbackup")).thenReturn(
        FactoryFlowDetail.empty("platformbackup"));

    FactoryFlowDetail deploy = service().flowDetail("deploy");
    FactoryFlowDetail backup = service().flowDetail("platformbackup");

    assertThat(deploy.items()).extracting(FactoryFlowDetail.Item::id)
        .containsExactly("deploy-prod");
    assertThat(backup.items()).isEmpty();
    verify(client, never()).factoryFlowDetail("deploy");
    verify(client, never()).factoryFlowDetail("platformbackup");
  }

  @Test
  void returnsAnEmptyDetailWhenTheFactoryCannotBeReached() {
    when(client.factoryFlowDetail("logwatch"))
        .thenThrow(new RestClientException("connection refused"));

    assertThat(service().flowDetail("logwatch").items()).isEmpty();
  }

  @Test
  void returnsAnEmptyDetailWhenTheDeployerCannotBeReached() {
    // Includes both a genuine outage and an unconfigured read token on that container: either
    // way RestClient throws for a non-2xx response, and this must degrade, never propagate.
    when(client.deployerFlowDetail("deploy"))
        .thenThrow(new RestClientException("connection refused"));

    assertThat(service().flowDetail("deploy").items()).isEmpty();
  }

  private FactoryAdminService service() {
    return new FactoryAdminService(client, mock(FactoryAdminProperties.class), null);
  }
}
