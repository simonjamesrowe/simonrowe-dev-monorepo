package com.simonrowe.factory.codereview.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime configuration for GitHub ingress and the replaceable agent runner. */
@ConfigurationProperties("factory.codereview")
public record CodeReviewProperties(
    Github github, Agent agent, Api api, String temporalUiBaseUrl) {

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

  /**
   * Authentication for internal factory endpoints.
   *
   * <p>Two separate values, deliberately: {@code triggerToken} authorises starting work — a
   * deploy, a code review, a platform backup — and {@code readToken} authorises only reading
   * titled per-run detail. The {@code deployer} holds the socket-holding container's
   * credentials and is granted {@code readToken} but never {@code triggerToken}, so a container
   * that must never start a deploy of itself cannot be tricked into doing so by this class.
   *
   * @param triggerToken authorises the admin package's
   *     {@code FactoryTokenAuthenticator.authenticate}
   * @param readToken authorises the admin package's
   *     {@code FactoryTokenAuthenticator.authenticateRead}
   */
  public record Api(String triggerToken, String readToken) {
  }
}
