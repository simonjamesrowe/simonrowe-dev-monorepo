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
    List<String> deployerBlock = ComposeFile.everyDeployer();

    assertThat(ComposeFile.declaredKeysContaining(deployerBlock, FORBIDDEN_FRAGMENT))
        .as("""
            `deployer` holds the Docker socket and must never hold the credential that \
            authorises starting a deploy of itself""")
        .isEmpty();
  }

  @Test
  void deployerDeclaresTheReadToken() throws IOException {
    // Without this, the deploy/platformbackup detail panel silently degrades to an empty list
    // that looks identical to a genuinely quiet module. Both deployers: each answers for its own
    // host's deploy runs.
    for (var deployer : ComposeFile.deployerBlocks().entrySet()) {
      assertThat(ComposeFile.declaredKeysContaining(deployer.getValue(), "FACTORY_READ_TOKEN"))
          .withFailMessage("the deployer in %s declares no FACTORY_READ_TOKEN", deployer.getKey())
          .isNotEmpty();
    }
    List<String> deployerBlock = ComposeFile.everyDeployer();

    assertThat(ComposeFile.declaredKeysContaining(deployerBlock, "FACTORY_READ_TOKEN"))
        .as("""
            `deployer` must hold FACTORY_READ_TOKEN so it can answer \
            GET /api/factory/flow/{nodeKey} for the nodes it owns""")
        .isNotEmpty();
  }

  @Test
  void softwareFactoryDeclaresBothTokens() throws IOException {
    List<String> factoryBlock = ComposeFile.softwareFactory();

    assertThat(ComposeFile.declaredKeysContaining(factoryBlock, "FACTORY_TRIGGER_TOKEN"))
        .isNotEmpty();
    assertThat(ComposeFile.declaredKeysContaining(factoryBlock, "FACTORY_READ_TOKEN"))
        .isNotEmpty();
  }

  @Test
  void bothFactoryReadTokenOccurrencesUseTheOptionalInterpolationForm() throws IOException {
    // This exact mistake was made once already during implementation: `:?` on a variable absent
    // from the prod .env fails interpolation for the WHOLE compose file, which wedges sync-config
    // AND takes down monitor-prod.sh's minutely `up -d` - the self-healing watchdog. Assert both
    // the deployer's and software-factory's lines use `:-`, and that neither ever uses `:?`.
    List<String> occurrences = java.util.stream.Stream.concat(
            ComposeFile.lines().stream(), ComposeFile.factoryLines().stream())
        .filter(line -> line.contains("FACTORY_READ_TOKEN:"))
        .filter(line -> !line.trim().startsWith("#"))
        .toList();

    assertThat(occurrences)
        .as("""
            expected exactly three FACTORY_READ_TOKEN assignment lines: the Pi's deployer, and \
            software-factory and the deployer on the factory host""")
        .hasSize(3);
    assertThat(occurrences)
        .as("""
            every FACTORY_READ_TOKEN assignment must use the optional ${FACTORY_READ_TOKEN:-} \
            form, or an unset variable fails interpolation for the whole compose file: %s""",
            occurrences)
        .allMatch(line -> line.contains("${FACTORY_READ_TOKEN:-}"));
    assertThat(occurrences)
        .as("""
            no FACTORY_READ_TOKEN assignment may use the required ${FACTORY_READ_TOKEN:?} form: \
            %s""", occurrences)
        .noneMatch(line -> line.contains(":?"));
  }
}
