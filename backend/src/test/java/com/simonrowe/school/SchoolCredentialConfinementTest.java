package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces credential confinement in {@code docker-compose.prod.yml}.
 *
 * <p>The Gmail refresh token reads a mailbox of correspondence about children. It belongs to the
 * {@code backend} service and nowhere else — in particular it must never reach {@code deployer},
 * which holds {@code /var/run/docker.sock} and is therefore root-equivalent on the host.
 *
 * <p>Matching on names <b>containing</b> the fragment rather than starting with it, for the reason
 * {@code DeployerLinearCredentialTest} records: a prefix match catches the credential and misses
 * the flag that activates it, and a flag that switches a credential-reading component on inside
 * the socket-holding JVM is as dangerous as the credential.
 *
 * <p>Reads the compose file directly rather than trusting the Spring-side
 * {@code @ConditionalOnProperty} gates. Those stop the beans registering; nothing stops a future
 * compose edit putting the variable in the process environment of the wrong container.
 */
class SchoolCredentialConfinementTest {

  private static final Path COMPOSE = Path.of("..", "docker-compose.prod.yml");
  private static final Pattern SERVICE = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
  private static final Pattern ENV_KEY = Pattern.compile("^      ([A-Z0-9_]+):");

  @Test
  @DisplayName("the deployer holds no school or Gmail variable")
  void deployerHoldsNothing() throws IOException {
    assertThat(schoolVariablesFor("deployer"))
        .as("`deployer` holds /var/run/docker.sock. The Gmail token reads a mailbox about "
            + "children and belongs only on `backend`. See docs/runbooks/term-time.md.")
        .isEmpty();
  }

  @Test
  @DisplayName("software-factory holds no school or Gmail variable either")
  void softwareFactoryHoldsNothing() throws IOException {
    assertThat(schoolVariablesFor("software-factory"))
        .as("`software-factory` shares an image with `deployer` and has no reason to read "
            + "school mail.")
        .isEmpty();
  }

  @Test
  @DisplayName("the backend does hold them, so the assertions above cannot pass vacuously")
  void backendHoldsThem() throws IOException {
    // Without this, deleting every variable from the file would look like three passes while
    // silently disabling the feature.
    assertThat(schoolVariablesFor("backend"))
        .as("`backend` is where Term Time's configuration belongs")
        .isNotEmpty();
  }

  @Test
  @DisplayName("no school variable uses the ${VAR:?} required form")
  void noRequiredVariables() throws IOException {
    // A ${VAR:?} that is unset fails interpolation for the WHOLE compose file, which wedges
    // sync-config and takes monitor-prod.sh's minutely `up -d` down with it. The same trap
    // trivy-server's --token already documents.
    final List<String> offenders = Files.readAllLines(COMPOSE).stream()
        .filter(line -> line.contains("SCHOOL_") && line.contains(":?"))
        .toList();

    assertThat(offenders)
        .as("use the ${VAR:-} empty-default form for every school variable")
        .isEmpty();
  }

  private List<String> schoolVariablesFor(final String service) throws IOException {
    final List<String> found = new ArrayList<>();
    boolean inside = false;
    for (String line : Files.readAllLines(COMPOSE)) {
      final Matcher serviceMatch = SERVICE.matcher(line);
      if (serviceMatch.matches()) {
        inside = serviceMatch.group(1).equals(service);
        continue;
      }
      if (!inside) {
        continue;
      }
      final Matcher keyMatch = ENV_KEY.matcher(line);
      if (keyMatch.find()) {
        final String key = keyMatch.group(1);
        if (key.contains("SCHOOL") || key.contains("GMAIL")) {
          found.add(key);
        }
      }
    }
    return found;
  }
}
