package com.simonrowe.factory.writing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.testsupport.ComposeFile;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Keeps the content-writing queue on {@code software-factory} in {@code docker-compose.prod.yml}.
 *
 * <p>The {@code deployer} runs the same image and holds {@code /var/run/docker.sock}. A
 * {@code FACTORY_WRITING_ENABLED} there would register a Claude-running activity in the
 * root-equivalent container and split jobs between two builds, since the deployer is updated by
 * hand and routinely lags. The Java gate stops neither a future compose edit nor a copy-paste, so
 * this reads the file the way a reviewer would, as {@code DeployerLinearCredentialTest} does.
 */
class WritingComposeTest {

  private static final String FRAGMENT = "FACTORY_WRITING";

  @Test
  void theDeployerCarriesNoWritingVariable() throws IOException {
    List<String> deployer = ComposeFile.serviceBlock(ComposeFile.lines(), "deployer");

    assertThat(deployer)
        .as("the block boundaries must be right, or this passes by reading nothing")
        .anySatisfy(line -> assertThat(line).contains("FACTORY_DEPLOY_ENABLED"));
    assertThat(ComposeFile.declaredKeysContaining(deployer, FRAGMENT))
        .as("content writing runs on software-factory only, never on the socket holder")
        .isEmpty();
  }

  @Test
  void softwareFactoryCarriesTheEnableFlag() throws IOException {
    // The mirror of the assertion above: without it, deleting the flag from both services would
    // pass while switching the queue off everywhere, and every release note would quietly fall
    // back to OpenAI.
    List<String> factory = ComposeFile.serviceBlock(ComposeFile.lines(), "software-factory");

    assertThat(ComposeFile.declaredKeysContaining(factory, FRAGMENT))
        .contains("FACTORY_WRITING_ENABLED");
  }
}
