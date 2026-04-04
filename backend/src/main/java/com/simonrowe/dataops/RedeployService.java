package com.simonrowe.dataops;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
      // Step 1: Pull all images
      operationsService.updateProgress("Pulling container images...", 5);
      for (int i = 0; i < properties.services().size(); i++) {
        String service = properties.services().get(i);
        operationsService.updateProgress(
            "Pulling " + service + " image...",
            5 + (i * 45 / properties.services().size()));
        runComposeCommand(List.of("pull", service), "Image pull failed for " + service);
      }

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

      // Step 3: Persist completed status before self-restart
      operationsService.updateProgress("Preparing backend restart...", 85);
      operationsService.completeOperation(
          "Redeploy complete. Backend restarting in "
              + properties.selfRestartDelaySeconds() + " seconds...");

      // Step 4: Schedule backend self-restart (fire-and-forget)
      LOG.info("Scheduling backend self-restart in {} seconds",
          properties.selfRestartDelaySeconds());
      Thread.startVirtualThread(() -> {
        try {
          Thread.sleep(properties.selfRestartDelaySeconds() * 1000L);
          List<String> command = buildComposeCommand(List.of("up", "-d", "backend"));
          new ProcessBuilder(command)
              .directory(composeWorkingDir())
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

  private void runComposeCommand(
      final List<String> args,
      final String errorPrefix
  ) throws RedeployException {
    List<String> command = buildComposeCommand(args);
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
          LOG.info("[docker compose] {}", line);
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
