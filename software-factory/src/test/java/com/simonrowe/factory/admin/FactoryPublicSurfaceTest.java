package com.simonrowe.factory.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the factory's public routing surface against the nginx configuration itself.
 *
 * <p>040-software-factory-console added nine internal endpoints, all of them reachable only from
 * the container network. That property is asserted here rather than assumed, because the thing
 * that enforces it is one character in a different file: `location = /webhooks/github` is an
 * exact match, and turning it into a prefix would expose every one of them at once. A reviewer
 * looking at a Java diff has no reason to open the nginx conf, so this test does it for them.
 *
 * <p>A hand-rolled line scan rather than a config parser, matching
 * {@code DeployerLinearCredentialTest} and the backend's {@code NoHostProcessLaunchTest}: this
 * module carries no nginx grammar, and the shape being checked is one line.
 */
class FactoryPublicSurfaceTest {

  /** Gradle runs tests from the module directory, one level below the repo root. */
  private static final Path PROXY_CONF =
      Path.of("..", "config", "nginx", "nginx-proxy.conf");

  private static final String WEBHOOK_LOCATION = "location = /webhooks/github";

  @Test
  void routesTheWebhookAsAnExactMatch() throws IOException {
    // A prefix match here (`location /webhooks/`) would still serve the webhook, so nothing would
    // look broken — while quietly widening what else is reachable under that prefix.
    assertThat(lines()).anyMatch(line -> line.trim().equals(WEBHOOK_LOCATION + " {"));
  }

  @Test
  void routesNoOtherFactoryPath() throws IOException {
    List<String> proxied = lines().stream()
        .map(String::trim)
        .filter(line -> line.startsWith("location"))
        .toList();

    assertThat(proxied)
        .noneMatch(line -> line.contains("/api/factory"))
        .noneMatch(line -> line.contains("/api/reviews"))
        .noneMatch(line -> line.contains("/api/feedback"))
        .noneMatch(line -> line.contains("/api/vulnerability-scans"))
        .noneMatch(line -> line.contains("/api/platform-backups"))
        .noneMatch(line -> line.contains("/api/deploys"));
  }

  @Test
  void namesNoFactoryHostname() throws IOException {
    // There is deliberately no factory.simonrowe.dev. The fingerprint URLs the Linear sink mints
    // use that host precisely because it resolves to nothing — they are identifiers, not links.
    assertThat(lines())
        .filteredOn(line -> line.trim().startsWith("server_name"))
        .noneMatch(line -> line.contains("factory.simonrowe.dev"));
  }

  private static List<String> lines() throws IOException {
    assertThat(PROXY_CONF).exists();
    return Files.readAllLines(PROXY_CONF);
  }
}
