package com.simonrowe.factory.ticketagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.testsupport.TicketAgentTestProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ClaudeCliTicketAgentEngineTest {

  private final ClaudeCliRunner runner = mock(ClaudeCliRunner.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final ClaudeCliTicketAgentEngine engine =
      new ClaudeCliTicketAgentEngine(TicketAgentTestProperties.of(true), runner, mapper);

  /** The whole security model: file tools only, so the most a prompt can do is change a diff. */
  @Test
  void runsClaudeWithFileToolsOnlyInTheCheckout() {
    ArgumentCaptor<ClaudeCliRunner.Invocation> invocation =
        ArgumentCaptor.forClass(ClaudeCliRunner.Invocation.class);
    when(runner.runStructured(invocation.capture(), any())).thenReturn(mapper.readTree(
        "{\"changed\":true,\"summary\":\"s\",\"prTitle\":\"fix: x\",\"prBody\":\"b\"}"));

    AgentEdit edit = engine.attempt(Path.of("/tmp/checkout"), "brief", detail -> { });

    assertThat(edit).isEqualTo(new AgentEdit(true, "s", "fix: x", "b"));
    ClaudeCliRunner.Invocation sent = invocation.getValue();
    assertThat(sent.tools()).containsExactly("Read", "Glob", "Grep", "Edit", "Write")
        .doesNotContain("Bash", "WebFetch", "WebSearch");
    assertThat(sent.workingDirectory()).isEqualTo(Path.of("/tmp/checkout"));
    assertThat(sent.model()).isEqualTo("sonnet");
    assertThat(sent.schemaJson()).contains("prTitle");
  }

  @Test
  void answerOutsideTheSchemaIsAnError() {
    when(runner.runStructured(any(), any())).thenReturn(mapper.readTree("{\"changed\":\"maybe\"}"));

    assertThatThrownBy(() -> engine.attempt(Path.of("/tmp"), "brief", detail -> { }))
        .isInstanceOf(IllegalStateException.class);
  }
}
