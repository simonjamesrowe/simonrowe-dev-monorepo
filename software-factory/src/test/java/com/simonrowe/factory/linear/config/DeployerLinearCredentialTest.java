package com.simonrowe.factory.linear.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Enforces credential confinement in {@code docker-compose.prod.yml}: the {@code deployer}
 * service must never declare a {@code LINEAR_}-prefixed environment variable.
 *
 * <p>{@code LinearActivitiesImpl}'s class-level {@code @ConditionalOnProperty} keeps the Linear
 * credential out of the JVM even if it were present in the process environment, but nothing stops
 * a future compose edit handing the key straight to {@code deployer} — the container holding
 * {@code /var/run/docker.sock}, root-equivalent on the host. This test reads the compose file
 * itself, the same way a reviewer would, rather than trusting the Spring-side gate alone. See
 * {@code docs/runbooks/linear.md}.
 *
 * <p>A hand-rolled line scan rather than a YAML library, matching {@code NoHostProcessLaunchTest}
 * in the backend module: this module carries no YAML dependency, and one line of the compose file
 * is not worth adding one for.
 */
class DeployerLinearCredentialTest {

  /**
   * The Gradle test working directory is the module directory ({@code software-factory/}), one
   * level below the repo root where the compose file lives.
   */
  private static final Path COMPOSE_FILE = Path.of("..", "docker-compose.prod.yml");

  /** A top-level compose mapping key at two-space indent, e.g. {@code "  deployer:"}. */
  private static final Pattern SERVICE_HEADER = Pattern.compile("^ {2}([a-zA-Z0-9_-]+):\\s*$");

  /** A YAML {@code key: value} line, capturing the key. */
  private static final Pattern MAPPING_KEY = Pattern.compile("^\\s+([A-Za-z0-9_]+):.*");

  private static final String DEPLOYER_SERVICE = "deployer";
  private static final String FORBIDDEN_PREFIX = "LINEAR_";

  @Test
  void deployerServiceDeclaresNoLinearVariable() throws IOException {
    List<String> deployerBlock = extractServiceBlock(readComposeLines(), DEPLOYER_SERVICE);

    List<String> offendingKeys = new ArrayList<>();
    for (String line : deployerBlock) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        // Comments (including the one explaining this exact omission) are not declarations.
        continue;
      }
      Matcher key = MAPPING_KEY.matcher(line);
      if (key.matches() && key.group(1).startsWith(FORBIDDEN_PREFIX)) {
        offendingKeys.add(key.group(1));
      }
    }

    assertThat(offendingKeys)
        .as(
            "`deployer` holds /var/run/docker.sock and must hold as few other credentials as "
                + "possible; the Linear tracker key belongs only on `software-factory`. See "
                + "docs/runbooks/linear.md.")
        .isEmpty();
  }

  @Test
  void theScanActuallyFoundTheDeployerService() throws IOException {
    // Without this, a wrong path or a renamed service would make the assertion above pass
    // vacuously - by finding and reading nothing - while looking green.
    assertThat(COMPOSE_FILE)
        .as("docker-compose.prod.yml must exist one directory above the module")
        .exists();

    List<String> deployerBlock = extractServiceBlock(readComposeLines(), DEPLOYER_SERVICE);

    assertThat(deployerBlock).as("the `deployer:` service block must be non-empty").isNotEmpty();
    assertThat(deployerBlock)
        .as("the extracted block should contain deployer's own known settings, or the block "
            + "boundaries are wrong")
        .anySatisfy(line -> assertThat(line).contains("FACTORY_DEPLOY_ENABLED"));
  }

  private static List<String> readComposeLines() throws IOException {
    if (!Files.exists(COMPOSE_FILE)) {
      throw new AssertionError(
          "Could not find " + COMPOSE_FILE.toAbsolutePath() + " - this test assumes the Gradle "
              + "test working directory is the module directory, one level below the repo "
              + "root.");
    }
    return Files.readAllLines(COMPOSE_FILE);
  }

  /**
   * Isolates one top-level service's mapping: every line from its {@code "  <name>:"} header,
   * up to (excluding) the next line at the same indentation that also looks like a mapping key.
   *
   * @param lines the whole compose file, in order
   * @param serviceName the service to isolate, e.g. {@code deployer}
   * @return the lines belonging to that service, never including its own header line
   */
  private static List<String> extractServiceBlock(
      final List<String> lines, final String serviceName) {
    List<String> block = new ArrayList<>();
    boolean insideService = false;
    boolean found = false;
    for (String line : lines) {
      Matcher header = SERVICE_HEADER.matcher(line);
      if (header.matches()) {
        if (header.group(1).equals(serviceName)) {
          insideService = true;
          found = true;
          continue;
        } else if (insideService) {
          break;
        }
      }
      if (insideService) {
        block.add(line);
      }
    }
    if (!found) {
      throw new AssertionError(
          "Could not find a '"
              + serviceName
              + ":' service block in "
              + COMPOSE_FILE.toAbsolutePath()
              + " - has it been renamed or removed? This test must not pass by reading nothing.");
    }
    return block;
  }
}
