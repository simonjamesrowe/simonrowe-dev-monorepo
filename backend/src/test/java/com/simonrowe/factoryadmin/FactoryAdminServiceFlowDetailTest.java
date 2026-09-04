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
 * FactoryAdminServiceFlowTest}, but for node detail the deployer is never asked at all: it
 * deliberately holds no {@code FACTORY_TRIGGER_TOKEN} and the per-node endpoint is
 * token-protected, so every request there would be refused with a blank-token 503 regardless of
 * what was sent. An empty detail is the correct, honest answer for those two keys — never a
 * silently-swallowed failure indistinguishable from "no runs".
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
  }

  @Test
  void neverAsksAnyContainerForTheDeployerOwnedNodesDetail() {
    // Not "asks and swallows the failure" — never asked in the first place, because the deployer
    // structurally cannot answer this call.
    FactoryFlowDetail deploy = service().flowDetail("deploy");
    FactoryFlowDetail backup = service().flowDetail("platformbackup");

    assertThat(deploy.items()).isEmpty();
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

  private FactoryAdminService service() {
    return new FactoryAdminService(client, mock(FactoryAdminProperties.class), null);
  }
}
