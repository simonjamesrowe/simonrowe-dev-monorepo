package com.simonrowe.factory.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.testsupport.ComposeFile;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Enforces in {@code docker-compose.prod.yml} that {@code deployer} switches code review off.
 *
 * <p><strong>This asserts presence, where {@code DeployerLinearCredentialTest} and {@code
 * DeployerGrafanaCredentialTest} assert absence — and the inversion is the point.</strong> Those
 * two guard credentials whose Spring-side flags default to {@code false}, so an absent variable is
 * already the safe state and any declaration is the risk. {@code factory.codereview.enabled}
 * defaults to {@code true} instead, for the reason {@code ReviewActivitiesImpl} documents, so here
 * an <em>absent</em> variable is the unsafe state: {@code deployer} would fall back to the default,
 * register the activity bean, and start winning about half of all code-review activities on a
 * container that holds no GitHub App credential.
 *
 * <p>That failure is why this is worth a test rather than a comment. It is intermittent, it is
 * logged only in the other container, it can fail one review in {@code REVIEWING} and the next in
 * {@code PUBLISHING}, and it presents as {@code UnresolvedAddressException} — which reads as a
 * network fault while {@code getent} and {@code curl} from inside {@code software-factory} keep
 * succeeding. It also blocks every merge, because {@code Code Review} is a required check.
 *
 * <p>The value must be a literal rather than {@code ${FACTORY_CODEREVIEW_ENABLED:-false}}: this
 * container runs {@code docker compose} against this very file, and compose gives the process
 * environment precedence over {@code .env}, so an interpolated form could be flipped by a stray
 * variable — the trap already recorded for {@code FACTORY_DEPLOY_TRIGGER_ENABLED}.
 */
class DeployerCodeReviewGateTest {

  private static final String DEPLOYER_SERVICE = "deployer";
  private static final String FLAG = "FACTORY_CODEREVIEW_ENABLED";

  @Test
  void deployerServiceDisablesCodeReviewActivities() throws IOException {
    Optional<String> value = declaredValue(deployerBlock(), FLAG);

    assertThat(value)
        .as(
            FLAG
                + " must be declared on `deployer`. It is NOT safe to omit: "
                + "factory.codereview.enabled defaults to true, so an absent variable leaves this "
                + "container polling the code-review activity queue with no GitHub App "
                + "credential, failing roughly half of all reviews and blocking every merge.")
        .isPresent();

    assertThat(value.orElseThrow().replace("\"", "").replace("'", "").trim())
        .as(
            "%s must be exactly \"false\" on `deployer`, and a literal rather than an "
                + "interpolated ${...} form: this container runs `docker compose` against this "
                + "file and compose gives the process environment precedence over .env.",
            FLAG)
        .isEqualTo("false");
  }

  @Test
  void softwareFactoryDoesNotPinTheFlagOff() throws IOException {
    // The other half of the invariant. `software-factory` is the container that must run reviews;
    // it relies on the application.yml default of true, so the only thing that could break it is
    // an explicit "false" appearing here. An absent variable is correct and expected.
    Optional<String> value =
        declaredValue(ComposeFile.serviceBlock(ComposeFile.lines(), "software-factory"), FLAG);

    assertThat(value.map(raw -> raw.replace("\"", "").replace("'", "").trim()))
        .as(
            "`software-factory` must not pin %s off - that is the container that reviews pull "
                + "requests, and the required `Code Review` check would never go green again.",
            FLAG)
        .isNotEqualTo(Optional.of("false"));
  }

  @Test
  void theScanActuallyFoundTheDeployerService() throws IOException {
    // Without this, a wrong path or a renamed service would make the assertions above fail
    // confusingly, or a future refactor could make them pass by reading nothing.
    List<String> block = deployerBlock();

    assertThat(block).as("the `deployer:` service block must be non-empty").isNotEmpty();
    assertThat(block)
        .as("the extracted block should contain deployer's own known settings, or the block "
            + "boundaries are wrong")
        .anySatisfy(line -> assertThat(line).contains("FACTORY_DEPLOY_ENABLED"));
  }

  private static List<String> deployerBlock() throws IOException {
    return ComposeFile.serviceBlock(ComposeFile.lines(), DEPLOYER_SERVICE);
  }

  /**
   * Reads the value of a declared environment variable from a service block.
   *
   * <p>Comment lines are skipped, because the comments explaining these flags necessarily name
   * them — the same reasoning {@link ComposeFile#declaredKeysContaining} documents. A separate
   * reader rather than reusing that method because this assertion is about the value, not the
   * key: "declared" is not the invariant here, "declared as false" is.
   *
   * @param serviceBlock the lines of one service
   * @param name the exact environment variable name
   * @return its raw value, or empty if the variable is not declared
   */
  private static Optional<String> declaredValue(
      final List<String> serviceBlock, final String name) {
    for (String line : serviceBlock) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      if (trimmed.startsWith(name + ":")) {
        return Optional.of(trimmed.substring(name.length() + 1));
      }
    }
    return Optional.empty();
  }
}
