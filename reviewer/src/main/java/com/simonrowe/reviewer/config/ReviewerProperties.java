package com.simonrowe.reviewer.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime configuration for GitHub ingress and the replaceable agent runner. */
@ConfigurationProperties("reviewer")
public record ReviewerProperties(Github github, Agent agent, Api api) {

  /** GitHub API and webhook credentials. */
  public record Github(
      String apiBaseUrl,
      String token,
      String webhookSecret,
      String appClientId,
      String appPrivateKeyPath,
      Duration requestTimeout) {
  }

  /** Claude CLI process and workspace limits. */
  public record Agent(
      String command,
      String model,
      String effort,
      int maxTurns,
      Duration timeout,
      Path workspaceRoot,
      long maxDiffBytes,
      int maxChangedFiles,
      String promptVersion) {
  }

  /** Authentication for the internal manual-trigger endpoint. */
  public record Api(String triggerToken) {
  }
}
