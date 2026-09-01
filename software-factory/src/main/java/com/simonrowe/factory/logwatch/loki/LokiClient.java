package com.simonrowe.factory.logwatch.loki;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.Severity;
import com.simonrowe.factory.logwatch.signature.SeverityDetector;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads container logs from Grafana Cloud Loki.
 *
 * <p>Uses {@link HttpClient} rather than Spring's {@code RestClient} to match
 * {@code DependencyTrackClient}, the module this one is modelled on.
 *
 * <p>Two things about this API are worth knowing before changing anything here, both verified
 * live against the production tenant on 2026-08-31:
 *
 * <ul>
 *   <li><strong>Reads and writes are separately gated.</strong> Throughout the three-week outage
 *       that motivated this module, every read returned {@code 200} while not a single byte could
 *       be ingested. A working read proves nothing about the pipeline.
 *   <li><strong>An empty success is not an error.</strong> {@code {"status":"success"}} with no
 *       streams is the wire shape of both "quiet stack" and "nothing has been stored for weeks",
 *       and nothing in the response distinguishes them. That is why
 *       {@code SourceHealthChecker} exists.
 * </ul>
 */
@Component
public class LokiClient {

  /**
   * Pre-filter to narrow what is transferred. It is not the severity decision — that is
   * {@link SeverityDetector}'s job per line, because a line containing the word "error" is not
   * necessarily an error line.
   */
  private static final String QUERY = "{container=~\".+\"} |~ \"(?i)(error|warn)\"";

  private final LogWatchProperties.Loki config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /**
   * Creates a client scoped to the given Loki configuration.
   *
   * @param properties the module's configuration, of which the Loki block is used
   * @param objectMapper mapper used to parse Loki's JSON responses
   */
  public LokiClient(final LogWatchProperties properties, final ObjectMapper objectMapper) {
    this.config = properties.loki();
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build();
  }

  /**
   * Reads every {@code ERROR} or {@code WARN} line in the window, up to the budget.
   *
   * @param from window start, inclusive
   * @param to window end, exclusive
   * @param lineBudget the most lines to read
   * @return the classified lines; lines matching no severity detector are excluded
   * @throws LokiException if the request fails or returns a non-2xx status
   */
  public List<LogLine> linesIn(final Instant from, final Instant to, final int lineBudget) {
    String path =
        "/query_range?query=" + encode(QUERY)
            + "&start=" + nanos(from)
            + "&end=" + nanos(to)
            + "&limit=" + lineBudget
            + "&direction=forward";
    JsonNode result = get(path).path("data").path("result");

    List<LogLine> lines = new ArrayList<>();
    for (JsonNode stream : result) {
      String container = stream.path("stream").path("container").asText("");
      for (JsonNode entry : stream.path("values")) {
        if (!entry.isArray() || entry.size() < 2) {
          continue;
        }
        String raw = entry.get(1).asText("");
        Severity severity = SeverityDetector.detect(raw).orElse(null);
        if (severity == null) {
          continue;
        }
        lines.add(
            new LogLine(
                container, instantFromNanos(entry.get(0).asText("0")), severity, raw));
      }
    }
    return lines;
  }

  /**
   * Counts the distinct containers that produced any line in the window.
   *
   * <p>Deliberately a separate query over the {@code container} label rather than a count over
   * {@link #linesIn}: that read is filtered to errors and warnings, so a perfectly healthy stack
   * legitimately contributes nothing to it. Judging coverage on the filtered set would report a
   * quiet, healthy stack as blind.
   *
   * @param from window start
   * @param to window end
   * @return how many distinct container label values Loki holds for the window
   * @throws LokiException if the request fails or returns a non-2xx status
   */
  public int distinctContainers(final Instant from, final Instant to) {
    JsonNode values =
        get("/label/container/values?start=" + nanos(from) + "&end=" + nanos(to)).path("data");
    return values.isArray() ? values.size() : 0;
  }

  /** Whether enough is configured to attempt a read at all. */
  public boolean configured() {
    return config.configured();
  }

  private JsonNode get(final String path) {
    if (!config.configured()) {
      throw new LokiException("Loki is not configured: endpoint, user or API key is missing");
    }
    URI uri = URI.create(config.queryBase() + path);
    String credentials =
        Base64.getEncoder()
            .encodeToString(
                (config.user() + ":" + config.apiKey()).getBytes(StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", "Basic " + credentials)
            .timeout(config.requestTimeout())
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        // A 404 here almost always means the query base is doubled rather than that Loki is down:
        // GRAFANA_CLOUD_LOKI_ENDPOINT already contains /loki/api/v1. Say so, because the bare
        // "404 page not found" body carries no hint at all.
        throw new LokiException(
            "Loki returned " + response.statusCode() + " for " + uri.getPath()
                + (response.statusCode() == 404
                    ? " - a 404 usually means the query base is doubled; check queryBase()"
                    : "")
                + ": " + response.body());
      }
      return objectMapper.readTree(response.body());
    } catch (IOException | JacksonException exception) {
      // IOException is the HTTP send; JacksonException is readTree above. Jackson 3 throws
      // unchecked, so without naming it here a malformed Loki body would escape as a raw
      // parse error rather than the LokiException this module reports "I cannot see" with.
      throw new LokiException("Loki request failed: " + uri.getPath(), exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new LokiException("Interrupted while reading from Loki", exception);
    }
  }

  /**
   * Loki timestamps are <strong>nanoseconds</strong> since the epoch. A seconds value is accepted
   * and silently returns an empty result for a window about fifty years wide in the wrong place —
   * an empty success, which is precisely the shape this module must never misread as clean.
   */
  private static String nanos(final Instant instant) {
    return Long.toString(instant.getEpochSecond()) + String.format("%09d", instant.getNano());
  }

  private static Instant instantFromNanos(final String value) {
    try {
      long nanos = Long.parseLong(value);
      return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    } catch (NumberFormatException exception) {
      return Instant.EPOCH;
    }
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
