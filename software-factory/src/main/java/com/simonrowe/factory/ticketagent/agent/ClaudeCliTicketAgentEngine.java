package com.simonrowe.factory.ticketagent.agent;

import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Claude Code with file tools only.
 *
 * <p>No {@code Bash}, no web tools, no MCP. That is the whole security model of running an agent
 * on a prompt that quotes production log lines: the worst an injected instruction can do is change
 * the diff, which Java then checks against the push denylist before anything leaves the box, and
 * which CI, the reviewer and the merge rules judge after it does. It is also why the agent cannot
 * build or test: GitHub Actions is the only build environment, by design.
 */
@Component
public class ClaudeCliTicketAgentEngine implements TicketAgentEngine {

  static final List<String> TOOLS = List.of("Read", "Glob", "Grep", "Edit", "Write");

  static final List<String> ALLOWED_TOOLS =
      List.of("Read(./**)", "Edit(./**)", "Write(./**)", "Glob", "Grep");

  private final TicketAgentProperties properties;
  private final ClaudeCliRunner runner;
  private final ObjectMapper objectMapper;
  private final String schema;

  public ClaudeCliTicketAgentEngine(
      final TicketAgentProperties properties,
      final ClaudeCliRunner runner,
      final ObjectMapper objectMapper) {
    this.properties = properties;
    this.runner = runner;
    this.objectMapper = objectMapper;
    this.schema = loadSchema();
  }

  @Override
  public AgentEdit attempt(
      final Path checkout, final String brief, final Consumer<String> heartbeat) {
    TicketAgentProperties.Agent agent = properties.agent();
    JsonNode structured =
        runner.runStructured(
            new ClaudeCliRunner.Invocation(
                agent.command(),
                agent.model(),
                agent.effort(),
                agent.maxTurns(),
                agent.timeout(),
                TOOLS,
                ALLOWED_TOOLS,
                schema,
                brief,
                checkout),
            heartbeat);
    try {
      return objectMapper.treeToValue(structured, AgentEdit.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Ticket agent output did not match its schema", exception);
    }
  }

  private static String loadSchema() {
    try (InputStream input =
        ClaudeCliTicketAgentEngine.class.getResourceAsStream("/ticket-agent-schema.json")) {
      if (input == null) {
        throw new IllegalStateException("ticket-agent-schema.json is missing");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load the ticket agent schema", exception);
    }
  }
}
