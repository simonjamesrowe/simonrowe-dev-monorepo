package com.simonrowe.dataops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link CommandRunner} backed by {@link ProcessBuilder}.
 *
 * <p>Spawning processes from the backend is established practice here — {@link
 * RedeployService} already drives {@code docker compose} this way, and it works
 * under the GraalVM native image with no extra configuration.
 *
 * <p>Three details in here are load-bearing and easy to get wrong:
 *
 * <ol>
 *   <li><strong>stderr is drained on its own thread.</strong> A full stderr pipe
 *       with nobody reading it blocks the child process while its stdout still has
 *       data to give, so the whole backup hangs until the timeout. It is captured
 *       rather than merged into stdout, because stdout <em>is</em> the dump — a
 *       stray warning merged into it would corrupt the SQL.
 *   <li><strong>The exit code is checked only after stdout reaches EOF.</strong>
 *       Checking earlier is exactly how a truncated dump gets written into the
 *       archive and the backup reports success.
 *   <li><strong>Secrets go through the environment, never {@code argv}.</strong>
 *       Combined with {@code docker exec -e PGPASSWORD} (the bare name, no value,
 *       so the Docker CLI forwards it from its own environment), the password
 *       appears in no process's command line.
 * </ol>
 */
@Component
public class ProcessCommandRunner implements CommandRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessCommandRunner.class);
  private static final int COPY_BUFFER_BYTES = 64 * 1024;
  /** Cap on captured stderr, so a pathologically chatty tool cannot exhaust heap. */
  private static final int MAX_STDERR_BYTES = 64 * 1024;

  private final long timeoutMinutes;

  public ProcessCommandRunner(
      @Value("${backup.platform.command-timeout-minutes:120}") final long timeoutMinutes) {
    this.timeoutMinutes = timeoutMinutes;
  }

  @Override
  public long runToStream(final List<String> command,
      final Map<String, String> extraEnv, final OutputStream out)
      throws CommandFailedException {
    Process process = start(command, extraEnv);
    StderrCollector stderr = StderrCollector.draining(process.getErrorStream());
    long bytes;
    try (InputStream stdout = process.getInputStream()) {
      bytes = copy(stdout, out);
    } catch (IOException ex) {
      process.destroyForcibly();
      throw new CommandFailedException(
          describe(command) + " failed while streaming output: " + ex.getMessage(), ex);
    }
    // Only now — with stdout at EOF — is a zero exit code meaningful.
    awaitSuccess(process, command, stderr);
    LOG.debug("{} produced {} bytes", describe(command), bytes);
    return bytes;
  }

  @Override
  public String runCapturingOutput(final List<String> command,
      final Map<String, String> extraEnv) throws CommandFailedException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    runToStream(command, extraEnv, buffer);
    return buffer.toString(StandardCharsets.UTF_8).trim();
  }

  private Process start(final List<String> command, final Map<String, String> extraEnv)
      throws CommandFailedException {
    // Logged without the environment: extraEnv carries secrets by design.
    LOG.info("Running: {}", describe(command));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().putAll(extraEnv);
    try {
      return builder.start();
    } catch (IOException ex) {
      throw new CommandFailedException(
          describe(command) + " could not be started: " + ex.getMessage(), ex);
    }
  }

  private void awaitSuccess(final Process process, final List<String> command,
      final StderrCollector stderr) throws CommandFailedException {
    try {
      if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new CommandFailedException(
            describe(command) + " timed out after " + timeoutMinutes + " minutes");
      }
    } catch (InterruptedException ex) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new CommandFailedException(describe(command) + " was interrupted", ex);
    }
    if (process.exitValue() != 0) {
      throw new CommandFailedException(describe(command) + " exited with "
          + process.exitValue() + ": " + stderr.text());
    }
  }

  private static long copy(final InputStream from, final OutputStream to)
      throws IOException {
    byte[] buffer = new byte[COPY_BUFFER_BYTES];
    long total = 0;
    int read;
    while ((read = from.read(buffer)) != -1) {
      to.write(buffer, 0, read);
      total += read;
    }
    return total;
  }

  /**
   * Renders a command for logging. Arguments are included: secrets travel via the
   * environment, so nothing sensitive is here — that is the whole reason for the
   * environment-passing convention.
   */
  private static String describe(final List<String> command) {
    return "`" + String.join(" ", command) + "`";
  }

  /** Consumes a process's stderr on a virtual thread so its pipe cannot fill. */
  private static final class StderrCollector {

    private final StringBuilder text = new StringBuilder();
    private final Thread thread;

    private StderrCollector(final InputStream stream) {
      // A platform thread, deliberately, not a virtual one. The drain loop holds
      // a monitor (`synchronized (text)`) on every read, and a virtual thread that
      // blocks inside a synchronized block can pin its carrier thread — the
      // opposite of what virtual threads are for. The cost is negligible: one
      // short-lived thread per external command, so six per nightly backup.
      this.thread = new Thread(() -> {
        try (InputStream in = stream) {
          byte[] buffer = new byte[4096];
          int read;
          while ((read = in.read(buffer)) != -1) {
            // Keep reading even once the cap is hit: the point of this thread is
            // to stop the pipe filling, so it must drain to EOF regardless.
            synchronized (text) {
              if (text.length() < MAX_STDERR_BYTES) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
              }
            }
          }
        } catch (IOException ex) {
          LOG.debug("Failed reading stderr: {}", ex.getMessage());
        }
      }, "stderr-drainer");
      // Daemon: a drainer blocked on a wedged child process must never hold up
      // JVM shutdown.
      this.thread.setDaemon(true);
      this.thread.start();
    }

    static StderrCollector draining(final InputStream stream) {
      return new StderrCollector(stream);
    }

    /** The collected stderr, waiting briefly for the drainer to finish. */
    String text() {
      try {
        thread.join(5_000L);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      synchronized (text) {
        String collected = text.toString().trim();
        return collected.isEmpty() ? "(no stderr output)" : collected;
      }
    }
  }
}
