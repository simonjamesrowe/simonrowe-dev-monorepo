package com.simonrowe.dataops;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Runs an external command. The seam that makes {@link PlatformBackupService}
 * testable.
 *
 * <p>The platform backup is almost entirely orchestration of {@code docker exec}
 * calls. Wired straight to {@link ProcessBuilder}, the service would only be
 * testable on a host running the whole production stack — which in practice means
 * untested, on the one code path whose failures are invisible until someone needs
 * a restore. Behind this interface, a fake lets the tests assert archive contents,
 * failure handling, manifest contents and temp-file cleanup with no Docker at all.
 */
public interface CommandRunner {

  /**
   * Runs a command, streaming its standard output into {@code out}.
   *
   * <p>Streaming rather than buffering is deliberate: dump sizes here are
   * unbounded and the backend container is capped at 2 GB.
   *
   * @param command the command and its arguments
   * @param extraEnv variables added to the child process environment. Used to
   *     pass secrets without ever putting them in {@code argv}, where {@code ps}
   *     on the host could read them.
   * @param out destination for standard output; not closed by this method
   * @return the number of bytes written to {@code out}
   * @throws CommandFailedException if the command cannot start, exits non-zero, or
   *     exceeds its timeout
   */
  long runToStream(List<String> command, Map<String, String> extraEnv, OutputStream out)
      throws CommandFailedException;

  /**
   * Runs a command and returns its standard output as a trimmed string. For small,
   * known-size output only — {@code docker inspect}, a row count, a table list.
   *
   * @param command the command and its arguments
   * @param extraEnv variables added to the child process environment
   * @return standard output, trimmed
   * @throws CommandFailedException if the command cannot start, exits non-zero, or
   *     exceeds its timeout
   */
  String runCapturingOutput(List<String> command, Map<String, String> extraEnv)
      throws CommandFailedException;

  /**
   * A command that could not be started, exited non-zero, or timed out.
   *
   * <p>Checked deliberately. Every caller in the backup path has to decide what a
   * failed {@code pg_dump} means, and the answer is always "fail the whole
   * operation and upload nothing" — a half-captured archive that reports success
   * is the worst outcome this feature can produce.
   */
  class CommandFailedException extends Exception {

    private static final long serialVersionUID = 1L;

    public CommandFailedException(final String message) {
      super(message);
    }

    public CommandFailedException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
