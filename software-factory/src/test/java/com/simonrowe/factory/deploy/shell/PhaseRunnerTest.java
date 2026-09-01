package com.simonrowe.factory.deploy.shell;

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
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.domain.DeployPhase;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The Java/shell boundary.
 *
 * <p>Two things here are worth pinning. The argv is built as a list, never as a shell string —
 * the head SHA reaches this from a webhook, and interpolating it into a command line would be the
 * one place in the feature where injection was possible. And {@code key=value} parsing is how the
 * workflow learns what {@code sync-config} decided, so a parsing bug does not fail loudly; it
 * silently misreads a decision, which could mean rolling back a checkout that never moved.
 */
class PhaseRunnerTest {

  private final ProcessRunner processRunner = mock(ProcessRunner.class);

  private final DeployProperties properties =
      new DeployProperties(
          true, false, null, null, null, null,
          "/deploy/docker-compose.prod.yml",
          "/deploy/scripts/restart-prod.sh",
          "/deploy",
          "https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git",
          List.of("backend", "frontend"),
          List.of("backend", "nginx"),
          null, null, "/var/run/deploy-state", Duration.ofMinutes(30), null, false);

  private final PhaseRunner runner = new PhaseRunner(processRunner, properties);

  private void processReturns(final String stdout, final String stderr, final int exitCode) {
    when(processRunner.run(any(), any(), any(), anyMap(), anySet(), any(), any()))
        .thenReturn(new ProcessRunner.ProcessResult(exitCode, stdout, stderr));
  }

  @SuppressWarnings("unchecked")
  private List<String> capturedCommand() {
    ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
    verify(processRunner)
        .run(command.capture(), any(), any(), anyMap(), anySet(), any(), any());
    return command.getValue();
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> capturedEnvironment() {
    ArgumentCaptor<Map<String, String>> environment = ArgumentCaptor.forClass(Map.class);
    verify(processRunner)
        .run(any(), any(), any(), environment.capture(), anySet(), any(), any());
    return environment.getValue();
  }

  // ---------------------------------------------------------------------------
  // The command
  // ---------------------------------------------------------------------------

  @Test
  void invokesTheConfiguredScriptWithThePhaseArgument() {
    processReturns("", "", 0);

    runner.run(DeployPhase.VERIFY, null, null, false, message -> { });

    assertThat(capturedCommand())
        .containsExactly("bash", "/deploy/scripts/restart-prod.sh", "verify");
  }

  @Test
  void passesTheTargetShaAsSeparateArgument() {
    // A list, never an interpolated string. The sha arrives from a webhook, so this is the one
    // place a shell metacharacter could otherwise have mattered.
    processReturns("", "", 0);

    runner.run(DeployPhase.SYNC_CONFIG, "abc123; rm -rf /", null, false, message -> { });

    assertThat(capturedCommand())
        .containsExactly(
            "bash", "/deploy/scripts/restart-prod.sh", "sync-config", "abc123; rm -rf /");
  }

  @Test
  void omitsTheShaArgumentWhenThereIsNone() {
    processReturns("", "", 0);

    runner.run(DeployPhase.PULL, "   ", null, false, message -> { });

    assertThat(capturedCommand()).hasSize(3);
  }

  @Test
  void runsInTheDeployDirectoryNotTheScriptDirectory() {
    // sync-config runs git in the working directory, and the compose commands resolve .env
    // relative to it.
    processReturns("", "", 0);

    runner.run(DeployPhase.PULL, null, null, false, message -> { });

    verify(processRunner)
        .run(any(), eq(Path.of("/deploy")), any(), anyMap(), anySet(), any(), any());
  }

  @Test
  void refusesPhaseThatHasNoScriptArgument() {
    // TRIAGE and REPORT are Java-side phases. Asking the shell to run one is a programming
    // error, and should say so rather than invoking `bash restart-prod.sh null`.
    assertThatThrownBy(() -> runner.run(DeployPhase.TRIAGE, null, null, false, message -> { }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TRIAGE");
  }

  // ---------------------------------------------------------------------------
  // The environment
  // ---------------------------------------------------------------------------

  @Test
  void passesTheScriptTheConfigurationItReads() {
    processReturns("", "", 0);

    runner.run(DeployPhase.RECREATE, null, null, false, message -> { });

    Map<String, String> environment = capturedEnvironment();
    assertThat(environment)
        .containsEntry("COMPOSE_FILE", "/deploy/docker-compose.prod.yml")
        // Space-separated, because the script iterates it with a bare `for`.
        .containsEntry("SERVICES", "backend frontend")
        .containsEntry("RECREATABLE", "backend nginx")
        .containsEntry("STATE_DIR", "/var/run/deploy-state")
        .containsEntry(
            "REPO_URL", "https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git");
  }

  @Test
  void passesTheImageTagOnlyWhenThereIsOne() {
    processReturns("", "", 0);
    runner.run(DeployPhase.PULL, null, "abc123", false, message -> { });
    assertThat(capturedEnvironment()).containsEntry("IMAGE_TAG", "abc123");
  }

  @Test
  void leavesTheImageTagToTheScriptDefaultWhenAbsent() {
    processReturns("", "", 0);
    runner.run(DeployPhase.PULL, null, null, false, message -> { });
    assertThat(capturedEnvironment()).doesNotContainKey("IMAGE_TAG");
  }

  @Test
  void setsDryRunOnlyWhenAsked() {
    processReturns("", "", 0);
    runner.run(DeployPhase.PULL, null, null, true, message -> { });
    assertThat(capturedEnvironment()).containsEntry("DRY_RUN", "1");
  }

  @Test
  void doesNotSetDryRunForRealDeploy() {
    processReturns("", "", 0);
    runner.run(DeployPhase.PULL, null, null, false, message -> { });
    assertThat(capturedEnvironment()).doesNotContainKey("DRY_RUN");
  }

  @Test
  void stripsNothingFromTheChildEnvironment() {
    // Unlike the agent, this child process IS the deploy and legitimately needs what it was
    // given. ClaudeCliRunner's allowlist exists because the agent reads untrusted branches.
    processReturns("", "", 0);

    runner.run(DeployPhase.PULL, null, null, false, message -> { });

    verify(processRunner).run(any(), any(), any(), anyMap(), eq(java.util.Set.of()), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Reading the result
  // ---------------------------------------------------------------------------

  @Test
  void keepsBothStreams() {
    // stdout carries the key=value contract, stderr the human narration. A failure diagnosis
    // needs both.
    processReturns("decision=applied\n", "Deploy directory fast-forwarded\n", 0);

    PhaseRunner.PhaseExecution execution =
        runner.run(DeployPhase.SYNC_CONFIG, "abc", null, false, message -> { });

    assertThat(execution.output()).contains("decision=applied").contains("fast-forwarded");
  }

  @Test
  void classifiesTheExitCodes() {
    processReturns("", "", 0);
    assertThat(runner.run(DeployPhase.PULL, null, null, false, m -> { }).succeeded()).isTrue();

    processReturns("", "", 1);
    PhaseRunner.PhaseExecution failed =
        runner.run(DeployPhase.PULL, null, null, false, m -> { });
    assertThat(failed.succeeded()).isFalse();
    assertThat(failed.declined()).isFalse();

    processReturns("", "", PhaseRunner.EXIT_DECLINED);
    PhaseRunner.PhaseExecution declined =
        runner.run(DeployPhase.PULL, null, null, false, m -> { });
    assertThat(declined.succeeded()).isFalse();
    assertThat(declined.declined()).isTrue();
  }

  @Test
  void parsesTheKeyValueContractFromStdout() {
    processReturns(
        """
        previous-sha=1111111111111111111111111111111111111111
        decision=held-back
        held-back=mongodb elasticsearch
        manual-command=docker compose -f docker-compose.prod.yml up -d mongodb
        """,
        "Held back for a human: mongodb elasticsearch\n",
        2);

    PhaseRunner.PhaseExecution execution =
        runner.run(DeployPhase.SYNC_CONFIG, "abc", null, false, message -> { });

    assertThat(execution.value("decision")).isEqualTo("held-back");
    assertThat(execution.value("previous-sha"))
        .isEqualTo("1111111111111111111111111111111111111111");
    // Values may contain spaces and further '=' signs; only the FIRST '=' separates.
    assertThat(execution.value("held-back")).isEqualTo("mongodb elasticsearch");
    assertThat(execution.value("manual-command"))
        .isEqualTo("docker compose -f docker-compose.prod.yml up -d mongodb");
  }

  @Test
  void ignoresOrdinaryProgressOutputRatherThanFailingOnIt() {
    // stdout also carries plain narration. A phase must not fail on the shape of its own logging.
    processReturns(
        """
        Pulling latest production images...
        decision=applied
        All containers settled.
        a sentence with = an equals sign in the middle
        """,
        "",
        0);

    PhaseRunner.PhaseExecution execution =
        runner.run(DeployPhase.SYNC_CONFIG, "abc", null, false, message -> { });

    assertThat(execution.value("decision")).isEqualTo("applied");
    assertThat(execution.value("Pulling latest production images...")).isNull();
    assertThat(execution.value("a sentence with ")).isNull();
  }

  @Test
  void returnsNullForKeyTheScriptDidNotEmit() {
    // The workflow relies on this: a missing `missing-variable` means there was none, not that
    // parsing failed.
    processReturns("decision=applied\n", "", 0);

    PhaseRunner.PhaseExecution execution =
        runner.run(DeployPhase.SYNC_CONFIG, "abc", null, false, message -> { });

    assertThat(execution.value("missing-variable")).isNull();
    assertThat(execution.value("held-back")).isNull();
  }

  @Test
  void takesTheLastValueWhenKeyRepeats() {
    processReturns("decision=applied\ndecision=held-back\n", "", 0);

    assertThat(
            runner
                .run(DeployPhase.SYNC_CONFIG, "abc", null, false, message -> { })
                .value("decision"))
        .isEqualTo("held-back");
  }

  // ---------------------------------------------------------------------------
  // capture()
  // ---------------------------------------------------------------------------

  @Test
  void captureRunsRawScriptArgumentWithoutPhase() {
    // compose-ps, container-logs and commit-range are evidence gathering, not deploy phases —
    // keeping them out of DeployPhase stops them appearing in a run record's phase list.
    processReturns("NAME  STATE\n", "", 0);

    PhaseRunner.PhaseExecution execution =
        runner.capture("compose-ps", message -> { });

    assertThat(capturedCommand())
        .containsExactly("bash", "/deploy/scripts/restart-prod.sh", "compose-ps");
    assertThat(execution.output()).contains("NAME");
  }

  @Test
  void captureForwardsExtraArguments() {
    processReturns("", "", 0);

    runner.capture("commit-range", message -> { }, "old-sha", "new-sha");

    assertThat(capturedCommand())
        .containsExactly(
            "bash", "/deploy/scripts/restart-prod.sh", "commit-range", "old-sha", "new-sha");
  }

  @Test
  void captureNeverRunsUnderDryRun() {
    // Evidence gathering is read-only, and a dry run of it would return nothing to diagnose.
    processReturns("", "", 0);

    runner.capture("compose-ps", message -> { });

    assertThat(capturedEnvironment()).doesNotContainKey("DRY_RUN");
  }
}
