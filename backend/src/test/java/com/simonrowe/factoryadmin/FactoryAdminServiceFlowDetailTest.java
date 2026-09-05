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
 * genuinely answer. Every failure still collapses to a result, never an exception — a drawer that
 * throws takes the whole page down for a detail panel — but it collapses to {@link
 * FactoryFlowDetail#unavailable(String)} (null {@code items}), never {@link
 * FactoryFlowDetail#empty(String)} (empty {@code items}): those mean different things to the
 * operator, and the whole point of this test class is that the two stay distinguishable.
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
  void returnsAnUnavailableDetailWhenTheFactoryCannotBeReached() {
    when(client.factoryFlowDetail("logwatch"))
        .thenThrow(new RestClientException("connection refused"));

    FactoryFlowDetail detail = service().flowDetail("logwatch");

    assertThat(detail.items()).isNull();
    assertThat(detail.nodeKey()).isEqualTo("logwatch");
  }

  @Test
  void returnsAnUnavailableDetailWhenTheDeployerCannotBeReached() {
    // Includes both a genuine outage and an unconfigured read token on that container: either
    // way RestClient throws for a non-2xx response, and this must degrade, never propagate.
    // Null items, not an empty list: an operator must not read this as "no runs in the last 30
    // days" when the truth is the console could not find out at all.
    when(client.deployerFlowDetail("deploy"))
        .thenThrow(new RestClientException("connection refused"));

    FactoryFlowDetail detail = service().flowDetail("deploy");

    assertThat(detail.items()).isNull();
    assertThat(detail.nodeKey()).isEqualTo("deploy");
  }

  @Test
  void passesThroughNullItemsFromTheFactoryWithoutDefaultingToAnEmptyList() {
    // software-factory itself already distinguishes "the source could not be read" (null items,
    // its own artifact reader failing) from "nothing to show" (empty items) — this method must
    // relay that fact rather than paper over it, which is what an empty-list default would do.
    when(client.factoryFlowDetail("linear")).thenReturn(new FactoryFlowDetail("linear", null));

    FactoryFlowDetail detail = service().flowDetail("linear");

    assertThat(detail.items()).isNull();
  }

  @Test
  void genuinelyEmptyDownstreamResultStaysEmptyRatherThanUnavailable() {
    // The distinguishing case: this must NOT collapse to the same null-items shape as the two
    // failure tests above, or the two states would be indistinguishable to the operator again.
    when(client.deployerFlowDetail("platformbackup"))
        .thenReturn(FactoryFlowDetail.empty("platformbackup"));

    FactoryFlowDetail detail = service().flowDetail("platformbackup");

    assertThat(detail.items()).isNotNull();
    assertThat(detail.items()).isEmpty();
  }

  private FactoryAdminService service() {
    return new FactoryAdminService(client, mock(FactoryAdminProperties.class), null);
  }
}
