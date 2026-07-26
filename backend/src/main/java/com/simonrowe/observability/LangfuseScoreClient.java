package com.simonrowe.observability;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    return properties.isScoresEnabled()
        && properties.getPublicKey() != null && !properties.getPublicKey().isBlank()
        && properties.getSecretKey() != null && !properties.getSecretKey().isBlank();
  }

  private void post(final String traceId, final LangfuseScore score) {
    try {
      restClient.post()
          .uri(stripTrailingSlash(properties.getHost()) + "/api/public/scores")
          .header(HttpHeaders.AUTHORIZATION, basicAuth())
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of(
              "traceId", traceId,
              "name", score.name(),
              "value", score.value(),
              "dataType", score.dataType()))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      LOG.warn("Failed to submit Langfuse score '{}' for trace {}", score.name(), traceId, e);
    }
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
