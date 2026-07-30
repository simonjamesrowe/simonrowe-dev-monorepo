package com.simonrowe.factory.codereview.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Executes argument-safe child processes with bounded time and liveness heartbeats. */
@Component
public class ProcessRunner {

  private static final int MAX_CAPTURE_BYTES = 16 * 1024 * 1024;

  public ProcessResult run(
      final List<String> command,
      final Path workingDirectory,
      final String standardInput,
      final Map<String, String> environment,
      final Set<String> removedEnvironment,
      final Duration timeout,
      final Consumer<String> heartbeat) {
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
      removedEnvironment.forEach(builder.environment()::remove);
      builder.environment().putAll(environment);
      process = builder.start();

      if (standardInput != null) {
        process.getOutputStream().write(standardInput.getBytes(StandardCharsets.UTF_8));
      }
      process.getOutputStream().close();

      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      AtomicBoolean outputLimitExceeded = new AtomicBoolean();
      AtomicReference<IOException> readFailure = new AtomicReference<>();
      Process runningProcess = process;
      Thread stdoutReader =
          Thread.ofVirtual()
              .start(
                  () ->
                      copy(
                          runningProcess.getInputStream(),
                          stdout,
                          outputLimitExceeded,
                          readFailure));
      Thread stderrReader =
          Thread.ofVirtual()
              .start(
                  () ->
                      copy(
                          runningProcess.getErrorStream(),
                          stderr,
                          outputLimitExceeded,
                          readFailure));

      long deadline = System.nanoTime() + timeout.toNanos();
      while (!process.waitFor(10, TimeUnit.SECONDS)) {
        heartbeat.accept("Agent process is still running");
        if (System.nanoTime() >= deadline) {
          terminate(process);
          throw new IllegalStateException("Process timed out after " + timeout);
        }
      }
      stdoutReader.join();
      stderrReader.join();
      if (readFailure.get() != null) {
        throw new IllegalStateException("Unable to capture process output", readFailure.get());
      }
      if (outputLimitExceeded.get()) {
        throw new IllegalStateException(
            "Process output exceeded " + MAX_CAPTURE_BYTES + " bytes");
      }
      return new ProcessResult(
          process.exitValue(),
          stdout.toString(StandardCharsets.UTF_8),
          stderr.toString(StandardCharsets.UTF_8));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      if (process != null) {
        terminate(process);
      }
      throw new IllegalStateException("Process interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to start process: " + command.getFirst(), exception);
    }
  }

  private static void copy(
      final java.io.InputStream input,
      final ByteArrayOutputStream output,
      final AtomicBoolean outputLimitExceeded,
      final AtomicReference<IOException> readFailure) {
    try (input; output) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        int remaining = MAX_CAPTURE_BYTES - output.size();
        if (remaining > 0) {
          output.write(buffer, 0, Math.min(read, remaining));
        }
        if (read > remaining) {
          outputLimitExceeded.set(true);
        }
      }
    } catch (IOException exception) {
      readFailure.compareAndSet(null, exception);
    }
  }

  private static void terminate(final Process process) {
    process.destroy();
    try {
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  /** Captured child-process result. */
  public record ProcessResult(int exitCode, String standardOutput, String standardError) {
  }
}
