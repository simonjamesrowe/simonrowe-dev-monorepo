package com.simonrowe.factory.writing.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the content-writing queue.
 *
 * @param enabled registers the activity and accepts jobs; true on software-factory only
 * @param command the Claude Code binary
 * @param model the model alias, {@code haiku} by default
 * @param effort the reasoning effort
 * @param maxTurns the turn budget; a tool-less run needs two
 * @param timeout how long one CLI run may take
 * @param workspaceRoot where each run's empty working directory is created
 * @param maxPromptChars the largest prompt a job may carry
 */
@ConfigurationProperties("factory.writing")
public record WritingProperties(
    boolean enabled, String command, String model, String effort, int maxTurns,
    Duration timeout, Path workspaceRoot, int maxPromptChars) {

  /** The default prompt cap: a release prompt is about 6,000 characters. */
  public static final int DEFAULT_MAX_PROMPT_CHARS = 40_000;

  public WritingProperties {
    command = command == null || command.isBlank() ? "claude" : command;
    model = model == null || model.isBlank() ? "haiku" : model;
    effort = effort == null || effort.isBlank() ? "low" : effort;
    maxTurns = maxTurns <= 0 ? 3 : maxTurns;
    timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
    workspaceRoot =
        workspaceRoot == null
            ? Path.of(System.getProperty("java.io.tmpdir"), "software-factory")
            : workspaceRoot;
    maxPromptChars = maxPromptChars <= 0 ? DEFAULT_MAX_PROMPT_CHARS : maxPromptChars;
  }
}
