package com.simonrowe.factory.deploy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.workflow.DeployActivities;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeployTriageEngineTest {

  private final ClaudeCliRunner runner = mock(ClaudeCliRunner.class);
  private final DeployProperties properties =
      new DeployProperties(
          true, false, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null);
  private final DeployTriageEngine engine = new DeployTriageEngine(properties, runner);

  // ---------------------------------------------------------------------------
  // The tool surface. These are the assertions that keep the agent away from the socket.
  // ---------------------------------------------------------------------------

  @Test
  void grantsNoBashTool() {
    // The container this runs in holds /var/run/docker.sock. The agent is handed captured output
    // and asked to explain it; it must have no way to run a command.
    assertThat(DeployTriageEngine.tools()).doesNotContain("Bash");
    assertThat(DeployTriageEngine.allowedTools()).noneMatch(tool -> tool.startsWith("Bash"));
  }

  @Test
  void grantsNoWriteOrEditTool() {
    assertThat(DeployTriageEngine.tools()).containsExactly("Read", "Glob", "Grep");
  }

  @Test
  void scopesEveryReadToTheWorkingDirectory() {
    // Which is the evidence directory, and nothing else - not the deploy directory, and not the
    // repository.
    assertThat(DeployTriageEngine.allowedTools()).contains("Read(./**)");
    assertThat(DeployTriageEngine.allowedTools()).noneMatch(tool -> tool.contains("/workspace"));
    assertThat(DeployTriageEngine.allowedTools()).noneMatch(tool -> tool.contains(".."));
  }

  @Test
  void runsTheAgentInTheEvidenceDirectory() {
    when(runner.runStructured(any(), any())).thenReturn(structured());

    engine.diagnose(Path.of("/tmp/evidence-1"), message -> { });

    ArgumentCaptor<ClaudeCliRunner.Invocation> invocation =
        ArgumentCaptor.forClass(ClaudeCliRunner.Invocation.class);
    verify(runner).runStructured(invocation.capture(), any());
    assertThat(invocation.getValue().workingDirectory()).isEqualTo(Path.of("/tmp/evidence-1"));
    assertThat(invocation.getValue().tools()).doesNotContain("Bash");
    assertThat(invocation.getValue().schemaJson()).contains("suspectedCause");
  }

  @Test
  void tellsTheAgentThatLowConfidenceIsAnAcceptableAnswer() {
    // A confident-sounding guess sends the maintainer down the wrong path, which is worse than
    // "the logs do not say".
    when(runner.runStructured(any(), any())).thenReturn(structured());

    engine.diagnose(Path.of("/tmp/evidence-1"), message -> { });

    ArgumentCaptor<ClaudeCliRunner.Invocation> invocation =
        ArgumentCaptor.forClass(ClaudeCliRunner.Invocation.class);
    verify(runner).runStructured(invocation.capture(), any());
    assertThat(invocation.getValue().prompt())
        .contains("confidence to \"low\"")
        .contains("You have no shell");
  }

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  @Test
  void parsesTheStructuredDiagnosis() {
    when(runner.runStructured(any(), any())).thenReturn(structured());

    DeployActivities.Triage triage = engine.diagnose(Path.of("/tmp/evidence-1"), message -> { });

    assertThat(triage.headline()).isEqualTo("backend never became healthy");
    assertThat(triage.confidence()).isEqualTo("high");
    assertThat(triage.suspectedCause()).isEqualTo("container-startup");
    assertThat(triage.failingServices()).containsExactly("backend");
    assertThat(triage.suspectCommits()).containsExactly("abc1234 — changed the healthcheck");
    assertThat(triage.suggestedNextStep()).contains("docker compose logs backend");
  }

  @Test
  void defaultsToLowConfidenceWhenTheAgentOmitsIt() {
    when(runner.runStructured(any(), any()))
        .thenReturn(new ObjectMapper().createObjectNode().put("diagnosis", "something broke"));

    DeployActivities.Triage triage = engine.diagnose(Path.of("/tmp/evidence-1"), message -> { });

    // The fail-safe direction: an unqualified diagnosis must not read as a confident one.
    assertThat(triage.confidence()).isEqualTo("low");
    assertThat(triage.suspectedCause()).isEqualTo("unknown");
    assertThat(triage.failingServices()).isEmpty();
  }

  @Test
  void theUnavailableFallbackIsHonestRatherThanEmpty() {
    DeployActivities.Triage triage = DeployActivities.Triage.unavailable("claude exited 1");

    assertThat(triage.confidence()).isEqualTo("low");
    assertThat(triage.diagnosis()).contains("claude exited 1");
    assertThat(triage.suggestedNextStep()).isNotBlank();
  }

  private static com.fasterxml.jackson.databind.JsonNode structured() {
    try {
      return new ObjectMapper()
          .readTree(
              """
              {
                "headline": "backend never became healthy",
                "diagnosis": "compose-ps.txt shows backend `starting` for the whole window.",
                "confidence": "high",
                "suspectedCause": "container-startup",
                "failingServices": ["backend"],
                "suspectCommits": [{"sha": "abc1234", "why": "changed the healthcheck"}],
                "suggestedNextStep": "Run `docker compose logs backend --tail 200` on the host."
              }
              """);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
