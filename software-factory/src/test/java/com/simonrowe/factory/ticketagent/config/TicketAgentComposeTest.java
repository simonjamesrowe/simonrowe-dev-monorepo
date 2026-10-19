package com.simonrowe.factory.ticketagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.testsupport.ComposeFile;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Keeps the ticket agent on {@code software-factory} in {@code docker-compose.prod.yml}.
 *
 * <p>The {@code deployer} runs the same image and holds {@code /var/run/docker.sock}. A
 * {@code FACTORY_TICKET_AGENT_ENABLED} there would clone, run an agent and push from the
 * root-equivalent container. The Java gate stops neither a future compose edit nor a copy-paste,
 * so this reads the file the way a reviewer would.
 */
class TicketAgentComposeTest {

  private static final String FRAGMENT = "FACTORY_TICKET_AGENT";

  @Test
  void theDeployerCarriesNoTicketAgentVariable() throws IOException {
    List<String> deployer = ComposeFile.serviceBlock(ComposeFile.lines(), "deployer");

    assertThat(deployer)
        .as("the block boundaries must be right, or this passes by reading nothing")
        .anySatisfy(line -> assertThat(line).contains("FACTORY_DEPLOY_ENABLED"));
    assertThat(ComposeFile.declaredKeysContaining(deployer, FRAGMENT)).isEmpty();
  }

  @Test
  void softwareFactoryCarriesTheEnableFlag() throws IOException {
    List<String> factory = ComposeFile.serviceBlock(ComposeFile.lines(), "software-factory");

    assertThat(ComposeFile.declaredKeysContaining(factory, FRAGMENT))
        .contains("FACTORY_TICKET_AGENT_ENABLED");
  }
}
