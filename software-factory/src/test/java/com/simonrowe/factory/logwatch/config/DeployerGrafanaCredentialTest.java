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
