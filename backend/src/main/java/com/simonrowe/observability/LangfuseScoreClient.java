package com.simonrowe.observability;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Submits scores to Langfuse. Scores are the only part of the Langfuse data model with no
 * OpenTelemetry representation, so they go over HTTP rather than through the Alloy pipeline.
 *
 * <p>Submission is fire-and-forget on the supplied executor and never propagates a failure:
 * an unreachable Langfuse must not break a chat turn.
 */
public class LangfuseScoreClient {

  private static final Logger LOG = LoggerFactory.getLogger(LangfuseScoreClient.class);

  private final RestClient restClient;
  private final LangfuseProperties properties;
  private final Executor executor;

  public LangfuseScoreClient(final RestClient.Builder builder,
      final LangfuseProperties properties, final Executor executor) {
    this.restClient = builder.build();
    this.properties = properties;
    this.executor = executor;
    logConfiguration();
  }

  /**
   * States once, at startup, whether scores will be submitted and why not if they will not.
   * {@link #submit} is a per-turn hot path and stays silent, so without this line a production
   * "no scores in Langfuse" report is undiagnosable from the logs.
   */
  private void logConfiguration() {
    if (!properties.isScoresEnabled()) {
      LOG.info("Langfuse score submission is DISABLED by configuration "
          + "(langfuse.scores-enabled=false); no chat-turn scores will be sent to {}.",
          properties.getHost());
    } else if (!hasCredentials()) {
      LOG.warn("Langfuse score submission is enabled but DISABLED IN PRACTICE: "
          + "langfuse.public-key/secret-key are missing or blank. Set LANGFUSE_PUBLIC_KEY and "
          + "LANGFUSE_SECRET_KEY to send chat-turn scores to {}.", properties.getHost());
    } else {
      LOG.info("Langfuse score submission is ENABLED: chat-turn scores will be sent to {} "
          + "in environment '{}'.", properties.getHost(), properties.getEnvironment());
    }
  }

  /**
   * Submits scores against a trace, asynchronously. Silently does nothing when scoring is
   * disabled, credentials are absent, or there is no trace to attach to.
   *
   * @param traceId the 32-hex W3C trace id, which Langfuse stores verbatim for OTLP traces
   * @param scores the scores to record
   */
  public void submit(final String traceId, final List<LangfuseScore> scores) {
    if (!enabled() || traceId == null || scores == null || scores.isEmpty()) {
      return;
    }
    for (LangfuseScore score : scores) {
      executor.execute(() -> post(traceId, score));
    }
  }

  private boolean enabled() {
    return properties.isScoresEnabled() && hasCredentials();
  }

  private boolean hasCredentials() {
    return properties.getPublicKey() != null && !properties.getPublicKey().isBlank()
        && properties.getSecretKey() != null && !properties.getSecretKey().isBlank();
  }

  private void post(final String traceId, final LangfuseScore score) {
    try {
      restClient.post()
          .uri(stripTrailingSlash(properties.getHost()) + "/api/public/scores")
          .header(HttpHeaders.AUTHORIZATION, basicAuth())
          .contentType(MediaType.APPLICATION_JSON)
          .body(body(traceId, score))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      LOG.warn("Failed to submit Langfuse score '{}' for trace {}", score.name(), traceId, e);
    }
  }

  /**
   * Builds the score payload. {@code environment} is load-bearing: the chat-turn span sets
   * {@code langfuse.environment}, so a score posted without it lands in Langfuse's {@code default}
   * environment bucket while its own trace is tagged {@code production} — every
   * environment-filtered score view and dashboard would then read empty.
   */
  private Map<String, Object> body(final String traceId, final LangfuseScore score) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("traceId", traceId);
    payload.put("name", score.name());
    payload.put("value", score.value());
    payload.put("dataType", score.dataType());
    String environment = properties.getEnvironment();
    if (environment != null && !environment.isBlank()) {
      payload.put("environment", environment);
    }
    return payload;
  }

  private String basicAuth() {
    String credentials = properties.getPublicKey() + ":" + properties.getSecretKey();
    return "Basic " + Base64.getEncoder()
        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private static String stripTrailingSlash(final String host) {
    return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
  }
}
