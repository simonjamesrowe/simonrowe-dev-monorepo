package com.simonrowe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the constitutional rule that the backend never launches a host process.
 *
 * <p>{@code RedeployService} was the only {@code ProcessBuilder} in {@code
 * backend/src/main/java}, and it existed to run {@code docker compose} — which is why the
 * container serving the public API had to hold {@code /var/run/docker.sock}, root-equivalent on
 * the host, plus copies of {@code docker-compose.prod.yml} and {@code .env}. Feature
 * 036-auto-deploy-on-merge deleted it and moved deployment to the {@code deployer} container,
 * which has no ingress at all.
 *
 * <p>Nothing stops that being reintroduced except this test. The next change that wants to shell
 * out from the backend will look reasonable in isolation, and re-adding the socket mount to get it
 * working will look like a small step — so the rule is checked by the build rather than
 * remembered. Constitution Principle II, since 2.0.0.
 *
 * <p>A source scan rather than bytecode analysis on purpose: it needs no new dependency, it reads
 * as the rule it enforces, and the failure message can say why.
 */
class NoHostProcessLaunchTest {

  private static final Path MAIN_SOURCE = Path.of("src/main/java");

  /** Ways of starting a host process from the JVM. All of them, not just the obvious one. */
  private static final List<String> FORBIDDEN =
      List.of("ProcessBuilder", "Runtime.getRuntime().exec", "Runtime.exec");

  @Test
  void noBackendSourceLaunchesHostProcess() throws IOException {
    List<String> offenders = new ArrayList<>();

    try (Stream<Path> sources = Files.walk(MAIN_SOURCE)) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        String content = Files.readString(source, StandardCharsets.UTF_8);
        for (String forbidden : FORBIDDEN) {
          if (content.contains(forbidden)) {
            offenders.add(source + " uses " + forbidden);
          }
        }
      }
    }

    assertThat(offenders)
        .as(
            "The backend must not launch host processes. Shelling out is what forced this "
                + "container to hold /var/run/docker.sock - host-root capability behind the "
                + "public API - along with the compose file and .env. Deployment belongs in the "
                + "`deployer` container, which has no ingress. See Constitution Principle II and "
                + "specs/036-auto-deploy-on-merge/.")
        .isEmpty();
  }

  @Test
  void theScanActuallyLooksAtSomething() {
    // Without this, a wrong MAIN_SOURCE path would make the test above pass vacuously and the
    // rule would be unenforced while looking green.
    assertThat(MAIN_SOURCE).exists();
    try (Stream<Path> sources = Files.walk(MAIN_SOURCE)) {
      assertThat(sources.filter(path -> path.toString().endsWith(".java")).count())
          .isGreaterThan(100);
    } catch (IOException exception) {
      throw new AssertionError("Could not read " + MAIN_SOURCE.toAbsolutePath(), exception);
    }
  }
}
