package com.simonrowe.factory.feedback.agent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.feedback.domain.Lesson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Integrates harvested lessons into guidance files with a writing-quality model. */
@Component
public class ClaudeCliDistillEngine implements DistillEngine {

  private final FeedbackProperties properties;
  private final ClaudeCliRunner runner;
  private final ObjectMapper objectMapper;
  private final String schema;

  public ClaudeCliDistillEngine(
      final FeedbackProperties properties,
      final ClaudeCliRunner runner,
      final ObjectMapper objectMapper) {
    this.properties = properties;
    this.runner = runner;
    this.objectMapper = objectMapper;
    this.schema = loadSchema();
  }

  @Override
  public DistillProposal distill(
      final DistillTarget target, final List<Lesson> lessons, final Consumer<String> heartbeat) {
    heartbeat.accept("Distilling guidance for " + target.owner() + "/" + target.repository());
    JsonNode structured =
        runner.runStructured(
            new ClaudeCliRunner.Invocation(
                properties.distill().command(),
                properties.distill().model(),
                properties.distill().effort(),
                properties.distill().maxTurns(),
                properties.distill().timeout(),
                List.of("Read", "Glob", "Grep", "Edit", "Write"),
                List.of("Read(./**)", "Edit(./**)", "Write(./**)", "Glob", "Grep"),
                schema,
                prompt(target, lessons, objectMapper),
                target.workspace()),
            heartbeat);
    try {
      return objectMapper.treeToValue(structured, DistillProposal.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Distill output did not match schema", exception);
    }
  }

  static String prompt(
      final DistillTarget target, final List<Lesson> lessons, final ObjectMapper objectMapper) {
    String lessonsJson;
    try {
      lessonsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(lessons);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to serialise lessons", exception);
    }
    String allowed =
        target.allowedPaths().stream()
            .map(path -> "- " + path)
            .reduce("", (first, second) -> first + second + "\n");
    return """
        You are updating agent guidance files in %s/%s (%s) so future coding agents follow the
        lessons below, which were learned from pull-request review feedback.

        Rules:
        - You may ONLY create or edit files matching these patterns (anything else fails a
          deterministic check and discards your work):
        %s
        - Read the existing guidance first. If a lesson is already covered, do not restate it.
        - Integrate minimally: extend an existing bullet or section where one fits; add the
          smallest new entry where none does. Keep instruction text terse and imperative.
        - Do not reorganise, reformat, or rewrite unrelated content.
        - If, after reading, nothing needs to change, change nothing and say so via
          changed=false with the reason.

        Lessons (JSON):
        %s

        When you are done, produce the structured result: changed, reason, and a conventional
        pull-request title (prefix "docs:") plus a body that lists each lesson applied with its
        evidence links.
        """
        .formatted(target.owner(), target.repository(), target.description(), allowed, lessonsJson);
  }

  private static String loadSchema() {
    try (InputStream input =
        ClaudeCliDistillEngine.class.getResourceAsStream("/distill-schema.json")) {
      if (input == null) {
        throw new IllegalStateException("distill-schema.json is missing");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load distill schema", exception);
    }
  }
}
