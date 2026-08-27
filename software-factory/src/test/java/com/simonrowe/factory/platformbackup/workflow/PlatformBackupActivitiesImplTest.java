package com.simonrowe.factory.platformbackup.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.agent.ProcessRunner;
import com.simonrowe.factory.platformbackup.config.PlatformBackupProperties;
import io.temporal.testing.TestActivityEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The Java/shell boundary for the backup.
 *
 * <p>The valuable assertions here are about what the activity does with a <em>failure</em>. A
 * non-zero exit means no archive reached Drive and nothing was pruned; if that were swallowed, the
 * workflow would report a successful backup on a night that produced none — which is the exact
 * failure the whole feature exists to prevent, and it would be invisible until a restore was
 * needed.
 */
class PlatformBackupActivitiesImplTest {

  private final ProcessRunner processRunner = mock(ProcessRunner.class);

  private final PlatformBackupProperties properties =
      new PlatformBackupProperties(
          true, "/workspace/repo/scripts/backup-platform.sh", "/workspace/repo",
          Duration.ofHours(6));

  private final TestActivityEnvironment environment = TestActivityEnvironment.newInstance();

  private PlatformBackupActivities activity() {
    environment.registerActivitiesImplementations(
        new PlatformBackupActivitiesImpl(processRunner, properties));
    return environment.newActivityStub(PlatformBackupActivities.class);
  }

  private void scriptExits(final int code, final String stdout, final String stderr) {
    when(processRunner.run(any(), any(), any(), anyMap(), anySet(), any(), any()))
        .thenReturn(new ProcessRunner.ProcessResult(code, stdout, stderr));
  }

  @Test
  void runsTheScriptAndReturnsItsOutput() {
    scriptExits(0, "", "[backup-platform] Done.\n");

    assertThat(activity().capture(false)).contains("Done.");
  }

  /**
   * The argv is built as a list, never as a shell string. Nothing user-controlled reaches it
   * today, but the deploy path made the same choice for the same reason and the two should not
   * diverge.
   */
  @Test
  void invokesTheConfiguredScriptFromTheDeployDirectory() {
    scriptExits(0, "", "ok");

    activity().capture(false);

    ArgumentCaptor<List<String>> command = ArgumentCaptor.captor();
    ArgumentCaptor<Path> workingDirectory = ArgumentCaptor.captor();
    verify(processRunner)
        .run(command.capture(), workingDirectory.capture(), any(), anyMap(), anySet(),
            eq(Duration.ofHours(6)), any());

    assertThat(command.getValue())
        .containsExactly("bash", "/workspace/repo/scripts/backup-platform.sh");
    assertThat(workingDirectory.getValue()).isEqualTo(Path.of("/workspace/repo"));
  }

  @Test
  void passesDryRunThroughToTheScript() {
    scriptExits(0, "", "ok");

    activity().capture(true);

    ArgumentCaptor<List<String>> command = ArgumentCaptor.captor();
    verify(processRunner)
        .run(command.capture(), any(), any(), anyMap(), anySet(), any(), any());

    assertThat(command.getValue()).endsWith("--dry-run");
  }

  /** The assertion this class exists for. */
  @Test
  void failsTheActivityWhenTheScriptExitsNonZero() {
    scriptExits(1, "", "[backup-platform] ERROR: pg_dump of 'dtrack' failed");

    assertThatThrownBy(() -> activity().capture(false))
        .hasMessageContaining("exited with 1")
        .hasMessageContaining("pg_dump of 'dtrack' failed");
  }

  /**
   * The script narrates on stderr, so a failure message that only appeared on stdout would be
   * dropped from the Temporal UI and the operator would see an exit code with no reason.
   */
  @Test
  void keepsBothOutputStreamsInTheFailureMessage() {
    scriptExits(2, "stdout detail", "stderr detail");

    assertThatThrownBy(() -> activity().capture(false))
        .hasMessageContaining("stdout detail")
        .hasMessageContaining("stderr detail");
  }
}
