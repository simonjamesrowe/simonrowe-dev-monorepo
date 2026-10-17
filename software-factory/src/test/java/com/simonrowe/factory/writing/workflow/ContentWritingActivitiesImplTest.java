package com.simonrowe.factory.writing.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.codereview.agent.ProcessRunner;
import com.simonrowe.factory.writing.config.WritingProperties;
import com.simonrowe.factory.writing.domain.WritingRequest;
import com.simonrowe.factory.writing.domain.WritingResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the real {@link ClaudeCliRunner} over a fake {@link ProcessRunner}, so the argv the CLI
 * would receive is asserted end to end without a CLI.
 */
class ContentWritingActivitiesImplTest {

  @TempDir
  Path root;

  private final ProcessRunner processRunner = mock(ProcessRunner.class);
  private final List<String> command = new ArrayList<>();
  private final AtomicReference<Path> workingDirectory = new AtomicReference<>();
  private final AtomicReference<Boolean> directoryWasEmpty = new AtomicReference<>();
  private final AtomicReference<String> standardInput = new AtomicReference<>();

  private ContentWritingActivitiesImpl activities() {
    return new ContentWritingActivitiesImpl(
        new WritingProperties(
            true, "claude", "haiku", "low", 3, Duration.ofMinutes(5), root, 0),
        new ClaudeCliRunner(processRunner, new ObjectMapper()));
  }

  private void cliAnswers(final int exitCode, final String stdout) {
    when(processRunner.run(anyList(), any(), anyString(), anyMap(), anySet(), any(), any()))
        .thenAnswer(
            invocation -> {
              command.addAll(invocation.getArgument(0));
              Path directory = invocation.getArgument(1);
              workingDirectory.set(directory);
              try (var entries = Files.list(directory)) {
                directoryWasEmpty.set(entries.findAny().isEmpty());
              }
              standardInput.set(invocation.getArgument(2));
              return new ProcessRunner.ProcessResult(exitCode, stdout, "");
            });
  }

  @Test
  void returnsTheTextFromTheStructuredOutput() {
    cliAnswers(0, "{\"structured_output\":{\"text\":\"  A release note.  \"}}");

    WritingResult result =
        activities().write(new WritingRequest("job-1", "Write a note."), detail -> { });

    assertThat(result.text()).isEqualTo("A release note.");
    assertThat(standardInput.get()).isEqualTo("Write a note.");
  }

  @Test
  void runsWithNoToolsAtAllAndTheFixedSchema() {
    cliAnswers(0, "{\"structured_output\":{\"text\":\"ok\"}}");

    activities().write(new WritingRequest("job-1", "prompt"), detail -> { });

    // --tools "" is the CLI's spelling for no built-in tools; a bare --allowedTools would make
    // the next flag ambiguous, so it must be absent rather than empty.
    assertThat(command).containsSequence("--tools", "", "--disallowedTools", "mcp__*");
    assertThat(command).doesNotContain("--allowedTools");
    assertThat(command).containsSequence("--model", "haiku");
    assertThat(command).containsSequence("--effort", "low");
    assertThat(command).containsSequence("--max-turns", "3");
    assertThat(command).containsSequence("--json-schema", ContentWritingActivitiesImpl.SCHEMA);
    assertThat(ContentWritingActivitiesImpl.SCHEMA)
        .contains("\"required\":[\"text\"]")
        .contains("\"additionalProperties\":false");
  }

  @Test
  void runsInFreshEmptyDirectoryThatIsDeletedAfterwards() {
    cliAnswers(0, "{\"structured_output\":{\"text\":\"ok\"}}");

    activities().write(new WritingRequest("job-1", "prompt"), detail -> { });

    assertThat(directoryWasEmpty.get()).isTrue();
    assertThat(workingDirectory.get().getParent()).isEqualTo(root.toAbsolutePath().normalize());
    assertThat(workingDirectory.get()).doesNotExist();
  }

  @Test
  void deletesTheDirectoryWhenTheCliFails() {
    cliAnswers(1, "{\"subtype\":\"error_max_turns\"}");

    assertThatThrownBy(
            () -> activities().write(new WritingRequest("job-1", "prompt"), detail -> { }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("error_max_turns");
    assertThat(workingDirectory.get()).doesNotExist();
  }

  @Test
  void refusesBlankText() {
    cliAnswers(0, "{\"structured_output\":{\"text\":\"   \"}}");

    assertThatThrownBy(
            () -> activities().write(new WritingRequest("job-1", "prompt"), detail -> { }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no text");
  }

  @Test
  void capsAnOverlongAnswer() {
    String huge = "x".repeat(ContentWritingActivitiesImpl.MAX_TEXT_CHARS + 50);
    cliAnswers(0, "{\"structured_output\":{\"text\":\"%s\"}}".formatted(huge));

    WritingResult result =
        activities().write(new WritingRequest("job-1", "prompt"), detail -> { });

    assertThat(result.text()).hasSize(ContentWritingActivitiesImpl.MAX_TEXT_CHARS);
  }
}
