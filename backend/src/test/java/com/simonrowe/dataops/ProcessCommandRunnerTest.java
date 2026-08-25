package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real process plumbing with {@code /bin/sh}, not Docker.
 *
 * <p>This class is where the subtle bugs in the platform backup would live — a
 * stderr pipe filling and deadlocking the child, or an exit code checked before
 * stdout reaches EOF so a truncated dump is archived as a success. Those are
 * behaviours of {@link ProcessBuilder}, not of Docker, so they can and should be
 * tested for real.
 */
class ProcessCommandRunnerTest {

  private final ProcessCommandRunner runner = new ProcessCommandRunner(1);

  private static List<String> sh(final String script) {
    return List.of("/bin/sh", "-c", script);
  }

  @Test
  void streamsStandardOutputAndReturnsTheByteCount() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    long bytes = runner.runToStream(sh("printf 'hello'"), Map.of(), out);

    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("hello");
    assertThat(bytes).isEqualTo(5L);
  }

  @Test
  void capturesTrimmedOutput() throws Exception {
    String output = runner.runCapturingOutput(sh("echo '  spaced  '"), Map.of());

    assertThat(output).isEqualTo("spaced");
  }

  @Test
  void failsOnNonZeroExitCodeAndReportsStderr() {
    assertThatThrownBy(() ->
        runner.runCapturingOutput(sh("echo 'the actual problem' >&2; exit 3"), Map.of()))
        .isInstanceOf(CommandRunner.CommandFailedException.class)
        .hasMessageContaining("exited with 3")
        .hasMessageContaining("the actual problem");
  }

  /**
   * The failure the design is most concerned with: a command that emits real
   * output and <em>then</em> fails must be reported as a failure, so the partial
   * output is never mistaken for a complete dump.
   */
  @Test
  void failsEvenWhenTheCommandProducedOutputBeforeExitingNonZero() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertThatThrownBy(() ->
        runner.runToStream(sh("printf 'partial dump'; exit 1"), Map.of(), out))
        .isInstanceOf(CommandRunner.CommandFailedException.class)
        .hasMessageContaining("exited with 1");

    // The partial output really was written — which is exactly why the exception
    // matters: the caller must discard the archive rather than upload it.
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("partial dump");
  }

  /**
   * Regression guard for the stderr deadlock. With stderr unread, the OS pipe
   * buffer (typically 64 KB) fills and the child blocks forever while stdout
   * still has data. 200 KB of stderr is comfortably past that threshold, so if
   * the draining thread is ever removed this test hangs and then fails on the
   * one-minute timeout rather than passing quietly.
   */
  @Test
  void doesNotDeadlockWhenTheCommandWritesMoreStderrThanThePipeHolds() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    String hundredBytes = "a".repeat(99);
    long bytes = runner.runToStream(
        sh("i=0; while [ $i -lt 2000 ]; do "
            + "printf '%s\\n' '" + hundredBytes + "' >&2; "
            + "i=$((i+1)); done; printf 'done'"),
        Map.of(), out);

    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("done");
    assertThat(bytes).isEqualTo(4L);
  }

  /**
   * stderr must not be merged into stdout: stdout <em>is</em> the SQL dump, so a
   * warning folded into it would corrupt the archive in a way that only surfaces
   * during a restore.
   */
  @Test
  void keepsStderrOutOfTheStreamedOutput() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    runner.runToStream(sh("printf 'DATA'; echo 'WARNING: noise' >&2"), Map.of(), out);

    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("DATA").doesNotContain("WARNING");
  }

  @Test
  void passesExtraEnvironmentVariablesToTheChild() throws Exception {
    String output = runner.runCapturingOutput(
        sh("printf '%s' \"$PGPASSWORD\""), Map.of("PGPASSWORD", "from-the-environment"));

    assertThat(output).isEqualTo("from-the-environment");
  }

  @Test
  void failsClearlyWhenTheCommandCannotBeStarted() {
    assertThatThrownBy(() ->
        runner.runCapturingOutput(List.of("/definitely/not/a/binary"), Map.of()))
        .isInstanceOf(CommandRunner.CommandFailedException.class)
        .hasMessageContaining("could not be started");
  }

  @Test
  void reportsAnEmptyStderrReadably() {
    assertThatThrownBy(() -> runner.runCapturingOutput(sh("exit 7"), Map.of()))
        .isInstanceOf(CommandRunner.CommandFailedException.class)
        .hasMessageContaining("(no stderr output)");
  }
}
