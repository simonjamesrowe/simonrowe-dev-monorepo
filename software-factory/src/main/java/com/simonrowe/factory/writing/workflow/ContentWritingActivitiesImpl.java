package com.simonrowe.factory.writing.workflow;

import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.writing.config.WritingProperties;
import com.simonrowe.factory.writing.config.WritingTaskQueues;
import com.simonrowe.factory.writing.domain.WritingRequest;
import com.simonrowe.factory.writing.domain.WritingResult;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Runs Claude Code once, with no tools at all, and returns the text it wrote.
 *
 * <p><strong>Terms of use.</strong> This runs on Simon's Claude subscription (the CLI's OAuth
 * token), not on a metered API key. Anthropic's Claude Code terms say subscription use is for
 * ordinary, individual use, and forbid routing requests through Free/Pro/Max credentials on
 * behalf of other users. So this queue is <em>only</em> for the site owner's own scheduled,
 * batch content jobs (release notes, the weekly digest). Never wire it to anything a site visitor
 * triggers. It also shares the subscription's 5-hour and weekly limits with Simon's interactive
 * use and with the code reviewer, which is why every caller must keep a fallback writer.
 *
 * <p><strong>No tools.</strong> The prompt carries untrusted text (commit messages written by
 * anyone with push access), and that is acceptable only because the agent cannot act on it:
 * {@code --tools ""} disables every built-in tool, no {@code --allowedTools} is passed, MCP is
 * denied, and the working directory is a fresh empty directory deleted afterwards. The worst a
 * prompt injection can do is change the text it returns, which the caller caps and stores as
 * plain prose.
 *
 * <p>The {@code @ConditionalOnProperty} keeps the {@code content-writing} activity poller on
 * {@code software-factory}. It is evaluated by the component scanner, so declaring this class
 * through an explicit {@code @Bean} method would register it unconditionally and silently ignore
 * the annotation. {@code WritingWorkerRegistrationTest} pins it by component-scanning for real.
 */
@Component
@ConditionalOnProperty(name = "factory.writing.enabled", havingValue = "true")
@ActivityImpl(taskQueues = WritingTaskQueues.CONTENT_WRITING)
public class ContentWritingActivitiesImpl implements ContentWritingActivities {

  /**
   * The fixed output contract. Generic on purpose: a job that needs structure can ask for it in
   * the prompt, and the factory never has to learn about it.
   */
  static final String SCHEMA =
      """
      {"type":"object","properties":{"text":{"type":"string"}},\
      "required":["text"],"additionalProperties":false}""";

  /**
   * Bounds the Temporal payload. Every caller stores far less (release notes keep 1,500), so text
   * past this is the model ignoring its brief.
   */
  static final int MAX_TEXT_CHARS = 20_000;

  private final WritingProperties properties;
  private final ClaudeCliRunner runner;

  public ContentWritingActivitiesImpl(
      final WritingProperties properties, final ClaudeCliRunner runner) {
    this.properties = properties;
    this.runner = runner;
  }

  @Override
  public WritingResult write(final WritingRequest request) {
    return write(request, detail -> Activity.getExecutionContext().heartbeat(detail));
  }

  WritingResult write(final WritingRequest request, final Consumer<String> heartbeat) {
    Path workspace = null;
    try {
      Path root = properties.workspaceRoot().toAbsolutePath().normalize();
      Files.createDirectories(root);
      workspace = Files.createTempDirectory(root, "writing-");
      heartbeat.accept("Writing " + request.jobId());
      JsonNode structured =
          runner.runStructured(
              new ClaudeCliRunner.Invocation(
                  properties.command(),
                  properties.model(),
                  properties.effort(),
                  properties.maxTurns(),
                  properties.timeout(),
                  List.of(),
                  List.of(),
                  SCHEMA,
                  request.prompt(),
                  workspace),
              heartbeat);
      JsonNode text = structured.path("text");
      if (!text.isString() || text.asString().isBlank()) {
        throw new IllegalStateException("Claude returned no text for " + request.jobId());
      }
      String written = text.asString().trim();
      return new WritingResult(
          written.length() <= MAX_TEXT_CHARS ? written : written.substring(0, MAX_TEXT_CHARS));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to prepare the writing directory", exception);
    } finally {
      if (workspace != null) {
        deleteTree(workspace);
      }
    }
  }

  private static void deleteTree(final Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(ContentWritingActivitiesImpl::deleteQuietly);
    } catch (IOException ignored) {
      // A failed cleanup must not hide the activity's own result or failure.
    }
  }

  private static void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup under a unique temporary directory.
    }
  }
}
