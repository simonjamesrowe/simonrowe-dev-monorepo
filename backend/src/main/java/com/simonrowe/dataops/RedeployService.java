package com.simonrowe.dataops;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RedeployService {

  private static final Logger LOG = LoggerFactory.getLogger(RedeployService.class);
  private static final long PROCESS_TIMEOUT_MINUTES = 5;

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
      operationsService.updateProgress("Restarting frontend and nginx...", 55);
      final List<String> nonSelfServices = properties.services().stream()
          .filter(s -> !"backend".equals(s))
          .toList();
      if (!nonSelfServices.isEmpty()) {
        List<String> upArgs = new ArrayList<>();
        upArgs.add("up");
        upArgs.add("-d");
        upArgs.addAll(nonSelfServices);
        runComposeCommand(upArgs, "Failed to restart services");
      }
      operationsService.updateProgress("Frontend and nginx restarted", 75);

      // Step 3: Resolve host compose directory (before persisting status so errors are caught)
      operationsService.updateProgress("Preparing backend restart...", 80);
      String hostComposeDir = resolveHostComposeDir();
      LOG.info("Resolved host compose directory: {}", hostComposeDir);

      // Step 4: Persist completed status before self-restart
      operationsService.updateProgress("Scheduling backend restart...", 85);
      operationsService.completeOperation(
          "Redeploy complete. Backend restarting in "
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

          List<String> command = new ArrayList<>();
          command.add(properties.dockerBinary());
          command.addAll(List.of(
              "run", "--rm", "-d",
              "--name", "backend-restarter",
              "-v", "/var/run/docker.sock:/var/run/docker.sock",
              "-v", hostComposeDir + ":" + hostComposeDir,
              "-w", hostComposeDir,
              properties.helperImage(),
              "docker", "compose",
              "-f", hostComposeFile,
              "-p", properties.projectName(),
              "up", "-d", "backend"));

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
