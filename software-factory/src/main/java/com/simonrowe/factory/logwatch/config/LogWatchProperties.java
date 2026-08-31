package com.simonrowe.factory.logwatch.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the scheduled log-watch scan.
 *
 * <p>Every flag defaults off, so merging this module cannot change production until an operator
 * opts in (Constitution II).
 *
 * @param enabled registers the activities, and so the Loki credential, in this container only
 * @param minimumOccurrences the floor below which a signature is not worth filing
 * @param maxPerRun how many signatures may be filed in one run
 * @param defaultWindow the window scanned when a trigger names none
 * @param lineBudget the most lines one scan will read
 * @param minimumContainers the coverage floor used when Alloy's component API is unreachable
 * @param loki where to read logs from
 * @param alloy where to ask whether the write path is healthy
 */
@ConfigurationProperties("factory.logwatch")
public record LogWatchProperties(
    boolean enabled,
    int minimumOccurrences,
    int maxPerRun,
    Duration defaultWindow,
    int lineBudget,
    int minimumContainers,
    Loki loki,
    Alloy alloy) {

  public LogWatchProperties {
    // Two, not one: a single occurrence of anything is noise at this scale. Provisional - the
    // spec's open questions flag this and maxPerRun as estimates until real production log
    // volumes can be sampled, which needs Loki to have been ingesting for a while.
    minimumOccurrences = minimumOccurrences <= 0 ? 2 : minimumOccurrences;
    maxPerRun = maxPerRun <= 0 ? 5 : maxPerRun;
    defaultWindow = defaultWindow == null ? Duration.ofHours(24) : defaultWindow;
    lineBudget = lineBudget <= 0 ? 5000 : lineBudget;
    minimumContainers = minimumContainers <= 0 ? 3 : minimumContainers;
    loki = loki == null ? Loki.defaults() : loki;
    alloy = alloy == null ? Alloy.defaults() : alloy;
  }

  /**
   * Grafana Cloud Loki endpoint and credential.
   *
   * @param endpoint the value of {@code GRAFANA_CLOUD_LOKI_ENDPOINT}, which is the <em>push</em>
   *     URL and already contains {@code /loki/api/v1}
   * @param user the numeric tenant id
   * @param apiKey the access-policy token, carrying both {@code logs:write} and {@code logs:read}
   * @param requestTimeout per-request timeout
   */
  public record Loki(String endpoint, String user, String apiKey, Duration requestTimeout) {

    public Loki {
      endpoint = endpoint == null ? "" : endpoint;
      user = user == null ? "" : user;
      apiKey = apiKey == null ? "" : apiKey;
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
    }

    static Loki defaults() {
      return new Loki(null, null, null, null);
    }

    /**
     * The query base: the configured endpoint with its trailing {@code /push} removed.
     *
     * <p>Load-bearing and easy to get wrong. {@code GRAFANA_CLOUD_LOKI_ENDPOINT} already ends in
     * {@code /loki/api/v1/push}, so appending {@code /api/v1/...} to the raw value produces
     * {@code /loki/api/v1/api/v1/...}, which returns a bare {@code 404 page not found} — plain
     * text, no JSON, and no hint that the path is doubled. Resolved once here rather than in
     * every caller.
     *
     * @return the base to append {@code /query_range} and friends to
     */
    public String queryBase() {
      String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
          : endpoint;
      return trimmed.endsWith("/push") ? trimmed.substring(0, trimmed.length() - "/push".length())
          : trimmed;
    }

    /** Whether enough is configured to attempt a read at all. */
    public boolean configured() {
      return !endpoint.isBlank() && !user.isBlank() && !apiKey.isBlank();
    }
  }

  /**
   * Alloy's own component API, used for the direct tier of the source-health check.
   *
   * <p>{@code alloy} publishes no host port and nginx routes nothing to it, but both containers
   * sit on the same compose network, so this needs no compose change. It is best-effort: when it
   * cannot be reached the health check falls back to inferring from container coverage.
   *
   * @param baseUrl Alloy's HTTP server, on the compose network
   * @param requestTimeout kept short, because this is an optional signal on the critical path
   */
  public record Alloy(String baseUrl, Duration requestTimeout) {

    public Alloy {
      baseUrl = baseUrl == null ? "http://alloy:12345" : baseUrl;
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
    }

    static Alloy defaults() {
      return new Alloy(null, null);
    }
  }
}
