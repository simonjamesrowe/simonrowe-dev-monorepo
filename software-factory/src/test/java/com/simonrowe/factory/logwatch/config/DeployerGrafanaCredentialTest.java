package com.simonrowe.factory.logwatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.testsupport.ComposeFile;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Enforces credential confinement in {@code docker-compose.prod.yml}: the {@code deployer} service
 * must never declare an environment variable whose name <strong>contains</strong>
 * {@code GRAFANA}.
 *
 * <p>Containing, not starting with, for the reason {@code DeployerLinearCredentialTest} documents:
 * a prefix match would catch {@code GRAFANA_CLOUD_API_KEY} and miss anything named the other way
 * round, and the flag that makes a credential useful is as dangerous as the credential.
 *
 * <p>{@code LogWatchActivitiesImpl}'s class-level {@code @ConditionalOnProperty} keeps the Loki
 * credential out of the {@code deployer} JVM even if it were present in the process environment,
 * but nothing stops a future compose edit handing it straight to the container holding
 * {@code /var/run/docker.sock}, which is root-equivalent on the host. This test reads the compose
 * file the way a reviewer would, rather than trusting the Spring-side gate alone (NFR-002).
 */
class DeployerGrafanaCredentialTest {

  private static final String DEPLOYER_SERVICE = "deployer";

  private static final String FORBIDDEN_FRAGMENT = "GRAFANA";

  @Test
  void deployerServiceDeclaresNoGrafanaVariable() throws IOException {
    List<String> deployerBlock =
        ComposeFile.serviceBlock(ComposeFile.lines(), DEPLOYER_SERVICE);

    assertThat(ComposeFile.declaredKeysContaining(deployerBlock, FORBIDDEN_FRAGMENT))
        .as(
            "`deployer` holds /var/run/docker.sock and must hold as few other credentials as "
                + "possible; the Grafana Cloud read key belongs only on `software-factory`. "
                + "See docs/runbooks/log-shipping.md.")
        .isEmpty();
  }

  @Test
  void softwareFactoryDoesDeclareTheCredential() throws IOException {
    // The mirror of the assertion above, and the one that stops it passing vacuously. Without it,
    // deleting the variable from both services would look like a pass while disabling the module.
    List<String> factoryBlock =
        ComposeFile.serviceBlock(ComposeFile.lines(), "software-factory");

    assertThat(ComposeFile.declaredKeysContaining(factoryBlock, FORBIDDEN_FRAGMENT))
        .as("`software-factory` is where the Loki read credential belongs")
        .isNotEmpty();
  }

  @Test
  void theDeployerCarriesNeitherLogWatchFlag() throws IOException {
    List<String> deployerBlock =
        ComposeFile.serviceBlock(ComposeFile.lines(), DEPLOYER_SERVICE);

    // Neither flag, for two different reasons.
    //
    // FACTORY_LOGWATCH_ENABLED would register the activity that reads Loki inside the container
    // holding the Docker socket - the confinement this whole file exists for.
    //
    // FACTORY_DEPLOY_LOG_WATCH_TRIGGER_ENABLED would simply do nothing here, and that is the
    // more insidious of the two: DeployWorkflowService runs on `software-factory` and reads the
    // flag there to build the DeployRequest, so setting it on the deployer leaves the real one
    // at its `false` default and no scan is ever scheduled, with no error anywhere. This is the
    // same mistake FACTORY_DEPLOY_TRIGGER_ENABLED made in 036, which is why that variable is
    // documented in the compose file as deliberately absent from this service.
    assertThat(ComposeFile.declaredKeysContaining(deployerBlock, "LOGWATCH"))
        .as("the module flag registers a Loki-reading activity; never on the socket holder")
        .isEmpty();
    assertThat(ComposeFile.declaredKeysContaining(deployerBlock, "LOG_WATCH"))
        .as("the trigger flag is read where the DeployRequest is built, which is not here")
        .isEmpty();
  }

  @Test
  void softwareFactoryCarriesTheTriggerFlag() throws IOException {
    List<String> factoryBlock =
        ComposeFile.serviceBlock(ComposeFile.lines(), "software-factory");

    // The mirror of the assertion above, and what stops it passing by the flag existing nowhere -
    // which would look identical from the deployer's side and disable the feature entirely.
    assertThat(ComposeFile.declaredKeysContaining(factoryBlock, "LOG_WATCH_TRIGGER"))
        .as("DeployWorkflowService reads this here to build the DeployRequest")
        .isNotEmpty();
  }

  @Test
  void theScanActuallyFoundTheDeployerService() throws IOException {
    // Without this, a wrong path or a renamed service would make the assertions above pass
    // vacuously - by finding and reading nothing - while looking green.
    assertThat(ComposeFile.PATH)
        .as("docker-compose.prod.yml must exist one directory above the module")
        .exists();

    List<String> deployerBlock =
        ComposeFile.serviceBlock(ComposeFile.lines(), DEPLOYER_SERVICE);

    assertThat(deployerBlock).as("the `deployer:` service block must be non-empty").isNotEmpty();
    assertThat(deployerBlock)
        .as("the extracted block should contain deployer's own known settings, or the block "
            + "boundaries are wrong")
        .anySatisfy(line -> assertThat(line).contains("FACTORY_DEPLOY_ENABLED"));
  }
}
