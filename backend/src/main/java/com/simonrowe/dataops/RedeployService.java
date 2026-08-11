package com.simonrowe.dataops;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RedeployService {

  private static final Logger LOG = LoggerFactory.getLogger(RedeployService.class);
  private static final long PROCESS_TIMEOUT_MINUTES = 5;
  private static final Set<String> ISOLATED_SERVICES = Set.of("software-factory");

  private final DataOperationsService operationsService;
  private final RedeployProperties properties;

  public RedeployService(
      final DataOperationsService operationsService,
      final RedeployProperties properties
  ) {
    this.operationsService = operationsService;
    this.properties = properties;
  }

  public boolean isDockerAvailable() {
    try {
      Process process = new ProcessBuilder(properties.dockerBinary(), "info")
          .redirectErrorStream(true)
          .start();
      boolean finished = process.waitFor(10, TimeUnit.SECONDS);
      return finished && process.exitValue() == 0;
    } catch (Exception ex) {
      LOG.warn("Docker availability check failed: {}", ex.getMessage());
      return false;
    }
  }

  public void performRedeploy() {
    try {
      // Step 1: Pull all service images
      operationsService.updateProgress("Pulling container images...", 5);
      for (int i = 0; i < properties.services().size(); i++) {
        String service = properties.services().get(i);
        operationsService.updateProgress(
            "Pulling " + service + " image...",
            5 + (i * 40 / properties.services().size()));
        runComposeCommand(List.of("pull", service), "Image pull failed for " + service);
      }

      // Pull helper image for backend self-restart
      operationsService.updateProgress("Pulling helper image...", 50);
      runDockerCommand(
          List.of("pull", properties.helperImage()),
          "Failed to pull helper image");

      // Step 2: Restart non-backend services
      final List<String> together = servicesRestartedTogether(properties.services());
      operationsService.updateProgress("Restarting " + String.join(" and ", together) + "...", 55);
      if (!together.isEmpty()) {
        List<String> upArgs = new ArrayList<>();
        upArgs.add("up");
        upArgs.add("-d");
        upArgs.addAll(together);
        runComposeCommand(upArgs, "Failed to restart services");
      }
      operationsService.updateProgress("Restarted " + String.join(" and ", together), 70);

      // Step 2b: Restart isolated services one at a time, each with --no-deps.
      final String isolatedNote = restartIsolatedServices();
      operationsService.updateProgress("Restarted remaining services", 75);

      // Step 3: Resolve host compose directory (before persisting status so errors are caught)
      operationsService.updateProgress("Preparing backend restart...", 80);
      String hostComposeDir = resolveHostComposeDir();
      LOG.info("Resolved host compose directory: {}", hostComposeDir);

      // Step 4: Persist completed status before self-restart
      operationsService.updateProgress("Scheduling backend restart...", 85);
      operationsService.completeOperation(
          "Redeploy complete." + isolatedNote + " Backend restarting in "
              + properties.selfRestartDelaySeconds() + " seconds...");

      // Step 5: Schedule backend self-restart via ephemeral helper container.
      // We must run the compose command from a separate container because
      // "docker compose up -d backend" stops this container to recreate it,
      // which would kill the compose CLI process mid-orchestration.
      LOG.info("Scheduling backend self-restart in {} seconds via helper container",
          properties.selfRestartDelaySeconds());

      String hostComposeFile = hostComposeDir + "/docker-compose.prod.yml";

      Thread.startVirtualThread(() -> {
        try {
          Thread.sleep(properties.selfRestartDelaySeconds() * 1000L);

          // Clean up any leftover helper container from a previous run
          new ProcessBuilder(properties.dockerBinary(), "rm", "-f", "backend-restarter")
              .redirectErrorStream(true)
              .start()
              .waitFor(5, TimeUnit.SECONDS);

          // Run compose in a helper container, then explicitly `docker start`
          // the canonical backend container as a safety net. We've seen the
          // compose recreate flow leave the new container in `created` state
          // (canonical name held by the now-stopped old container, so the new
          // one was auto-renamed and never started). --force-recreate +
          // explicit start removes ambiguity.
          String canonicalContainer = properties.projectName() + "-backend-1";
          String shellCmd = String.format(
              "docker compose -f %s -p %s up -d --force-recreate --no-deps backend; "
                  + "sleep 2; "
                  + "docker start %s 2>/dev/null || true",
              hostComposeFile, properties.projectName(), canonicalContainer);

          List<String> command = new ArrayList<>();
          command.add(properties.dockerBinary());
          command.addAll(List.of(
              "run", "--rm", "-d",
              "--name", "backend-restarter",
              "-v", "/var/run/docker.sock:/var/run/docker.sock",
              "-v", hostComposeDir + ":" + hostComposeDir,
              "-w", hostComposeDir,
              "--entrypoint", "sh",
              properties.helperImage(),
              "-c", shellCmd));

          LOG.info("Starting helper container: {}", String.join(" ", command));
          new ProcessBuilder(command)
              .inheritIO()
              .start();
        } catch (Exception ex) {
          LOG.error("Failed to schedule backend self-restart", ex);
        }
      });
    } catch (RedeployException ex) {
      LOG.error("Redeploy failed: {}", ex.getMessage());
      operationsService.failOperation(ex.getMessage());
    } catch (Exception ex) {
      LOG.error("Redeploy failed unexpectedly", ex);
      operationsService.failOperation("Redeploy failed: " + ex.getMessage());
    }
  }

  /**
   * Services safe to bring up in one command, letting Compose resolve their dependencies.
   *
   * <p>Excludes {@code backend}, which would kill the Compose process that is restarting it, and
   * anything in {@link #ISOLATED_SERVICES}.
   */
  static List<String> servicesRestartedTogether(final List<String> configured) {
    return configured.stream()
        .filter(s -> !"backend".equals(s))
        .filter(s -> !ISOLATED_SERVICES.contains(s))
        .toList();
  }

  /**
   * Services that must be brought up alone, with {@code --no-deps}.
   *
   * <p>{@code software-factory} declares {@code temporal} and {@code mongodb} as
   * {@code service_healthy} dependencies. Left in the main {@code up}, an unhealthy Temporal would
   * fail the whole command and frontend and nginx would never restart — the same shape as the
   * incident where an unhealthy backend left frontend in {@code created} and skipped nginx. It has
   * no relative bind mounts (only an absolute host path for the App key and a named volume), so it
   * recreates correctly from inside this container.
   */
  static List<String> servicesRestartedInIsolation(final List<String> configured) {
    return configured.stream().filter(ISOLATED_SERVICES::contains).toList();
  }

  /**
   * Restarts isolated services best-effort, returning a note for the completion message.
   *
   * <p>A failure here must not abort the redeploy: the backend self-restart is scheduled after this
   * step, and letting a software-factory problem strand the backend on its old image would be a
   * worse outcome than a stale reviewer. It is reported rather than swallowed — silence is exactly
   * how this service went stale in the first place.
   */
  private String restartIsolatedServices() {
    List<String> failed = new ArrayList<>();
    for (String service : servicesRestartedInIsolation(properties.services())) {
      operationsService.updateProgress("Restarting " + service + "...", 72);
      try {
        runComposeCommand(
            List.of("up", "-d", "--no-deps", service), "Failed to restart " + service);
      } catch (RedeployException ex) {
        LOG.error("Redeploy could not restart {}: {}", service, ex.getMessage());
        failed.add(service);
      }
    }
    return failed.isEmpty() ? "" : " WARNING: could not restart " + String.join(", ", failed) + ".";
  }

  private String resolveHostComposeDir() throws RedeployException {
    String containerId = System.getenv("HOSTNAME");
    if (containerId == null || containerId.isBlank()) {
      throw new RedeployException(
          "Cannot determine container ID from HOSTNAME environment variable");
    }

    try {
      String formatTemplate = "{{range .Mounts}}"
          + "{{if eq .Destination \"/workspace/docker-compose.prod.yml\"}}"
          + "{{.Source}}"
          + "{{end}}{{end}}";

      ProcessBuilder pb = new ProcessBuilder(
          properties.dockerBinary(), "inspect", "--format", formatTemplate, containerId
      ).redirectErrorStream(true);

      Process process = pb.start();
      String output;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.lines().collect(Collectors.joining()).trim();
      }

      boolean finished = process.waitFor(10, TimeUnit.SECONDS);
      if (!finished || process.exitValue() != 0 || output.isBlank()) {
        throw new RedeployException(
            "Failed to resolve host compose file path via docker inspect"
                + " (containerId=" + containerId + ", output=" + output + ")");
      }

      return new File(output).getParent();
    } catch (RedeployException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new RedeployException(
          "Failed to resolve host compose directory: " + ex.getMessage());
    }
  }

  private void runDockerCommand(
      final List<String> args,
      final String errorPrefix
  ) throws RedeployException {
    List<String> command = new ArrayList<>();
    command.add(properties.dockerBinary());
    command.addAll(args);
    runCommand(command, errorPrefix);
  }

  private void runComposeCommand(
      final List<String> args,
      final String errorPrefix
  ) throws RedeployException {
    List<String> command = buildComposeCommand(args);
    runCommand(command, errorPrefix);
  }

  private void runCommand(
      final List<String> command,
      final String errorPrefix
  ) throws RedeployException {
    LOG.info("Running: {}", String.join(" ", command));

    try {
      ProcessBuilder pb = new ProcessBuilder(command)
          .directory(composeWorkingDir())
          .redirectErrorStream(true);
      Process process = pb.start();

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          LOG.info("[docker] {}", line);
        }
      }

      boolean finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
      if (!finished) {
        process.destroyForcibly();
        throw new RedeployException(errorPrefix + ": process timed out after "
            + PROCESS_TIMEOUT_MINUTES + " minutes");
      }
      if (process.exitValue() != 0) {
        throw new RedeployException(errorPrefix + " (exit code: " + process.exitValue() + ")");
      }
    } catch (RedeployException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new RedeployException(errorPrefix + ": " + ex.getMessage());
    }
  }

  private List<String> buildComposeCommand(final List<String> args) {
    List<String> command = new ArrayList<>();
    command.add(properties.dockerBinary());
    command.add("compose");
    if (properties.projectName() != null && !properties.projectName().isBlank()) {
      command.add("-p");
      command.add(properties.projectName());
    }
    command.add("-f");
    command.add(properties.composeFile());
    command.addAll(args);
    return command;
  }

  private File composeWorkingDir() {
    return new File(properties.composeFile()).getParentFile();
  }

  private static class RedeployException extends Exception {
    RedeployException(final String message) {
      super(message);
    }
  }
}
