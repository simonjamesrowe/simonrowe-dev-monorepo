package com.simonrowe.factory.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.testsupport.ComposeFile;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Enforces the narrowing {@code docs/runbooks/software-factory.md} describes: the {@code
 * deployer} — the container holding {@code /var/run/docker.sock} — must be granted the read-only
 * {@code FACTORY_READ_TOKEN} (so it can answer {@code GET /api/factory/flow/{nodeKey}}
 * truthfully for the {@code deploy} and {@code platformbackup} nodes it owns) but must
 * <strong>never</strong> declare {@code FACTORY_TRIGGER_TOKEN}, which authorises every
 * trigger-protected endpoint including the one that starts a deploy.
 *
 * <p>Matches {@code TRIGGER_TOKEN} as a fragment rather than the whole variable name, on the same
 * reasoning as {@code DeployerLinearCredentialTest}: it is the specific, narrower substring that
 * distinguishes the forbidden trigger token from the permitted {@code FACTORY_READ_TOKEN}, which
 * does not contain it.
 */
class DeployerReadTokenConfinementTest {

  private static final String DEPLOYER_SERVICE = "deployer";
  private static final String FACTORY_SERVICE = "software-factory";
  private static final String FORBIDDEN_FRAGMENT = "TRIGGER_TOKEN";

  @Test
  void deployerDeclaresNoTriggerToken() throws IOException {
    List<String> deployerBlock = ComposeFile.serviceBlock(ComposeFile.lines(), DEPLOYER_SERVICE);

    assertThat(ComposeFile.declaredKeysContaining(deployerBlock, FORBIDDEN_FRAGMENT))
        .as("`deployer` holds the Docker socket and must never hold the credential that "
            + "authorises starting a deploy of itself")
        .isEmpty();
  }

  @Test
  void deployerDeclaresTheReadToken() throws IOException {
    // Without this, the deploy/platformbackup detail panel silently degrades to an empty list
    // that looks identical to a genuinely quiet module.
    List<String> deployerBlock = ComposeFile.serviceBlock(ComposeFile.lines(), DEPLOYER_SERVICE);

    assertThat(ComposeFile.declaredKeysContaining(deployerBlock, "FACTORY_READ_TOKEN"))
        .as("`deployer` must hold FACTORY_READ_TOKEN so it can answer "
            + "GET /api/factory/flow/{nodeKey} for the nodes it owns")
        .isNotEmpty();
  }

  @Test
  void softwareFactoryDeclaresBothTokens() throws IOException {
    List<String> factoryBlock = ComposeFile.serviceBlock(ComposeFile.lines(), FACTORY_SERVICE);

    assertThat(ComposeFile.declaredKeysContaining(factoryBlock, "FACTORY_TRIGGER_TOKEN"))
        .isNotEmpty();
    assertThat(ComposeFile.declaredKeysContaining(factoryBlock, "FACTORY_READ_TOKEN"))
        .isNotEmpty();
  }
}
