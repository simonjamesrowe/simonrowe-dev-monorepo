package com.simonrowe.factory.feedback.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime configuration for feedback harvesting and distillation. */
@ConfigurationProperties("factory.feedback")
public record FeedbackProperties(
    boolean enabled, List<String> repos, String skipLabel, String agentSetupRepo,
    String gitAuthorName, String gitAuthorEmail, Path workspaceRoot,
    Agent harvest, Agent distill) {

  public FeedbackProperties {
    repos = repos == null ? List.of() : List.copyOf(repos);
  }

  /** Claude CLI process and workspace configuration for agent phases. */
  public record Agent(
      String command, String model, String effort, int maxTurns, Duration timeout) {
  }
}
