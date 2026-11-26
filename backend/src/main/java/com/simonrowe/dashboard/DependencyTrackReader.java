package com.simonrowe.dashboard;

import com.simonrowe.dashboard.VulnerabilityReport.ProjectRow;
import com.simonrowe.dashboard.VulnerabilityReport.Status;
import com.simonrowe.dashboard.VulnerabilityReport.TrendPoint;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads project and portfolio metrics from Dependency-Track 5.
 *
 * <p>Uses the same {@code DEPENDENCYTRACK_API_KEY} as software-factory's CVE scan. The backend
 * already loads the whole {@code .env}, so the key needs no compose change; only the base URL
 * is set there, to the API server's address on the compose network.
 *
 * <p>Two requests per page view, both cheap: {@code /api/v1/project} carries each project's
 * current metrics inline, and the portfolio trend is one call. A failure degrades this panel
 * only, naming the status Dependency-Track answered with, because a {@code 401} here means the
 * key is wrong for the CVE scan too and that is worth seeing.
 */
@Component
public class DependencyTrackReader {

  private static final Logger LOG = LoggerFactory.getLogger(DependencyTrackReader.class);
  private static final ZoneId ZONE = ZoneId.of("Europe/London");
  static final int TREND_DAYS = 90;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final DashboardProperties.DependencyTrack config;
  private final Clock clock;
  private final HttpClient httpClient;

  /**
   * Creates the reader.
   *
   * @param properties dashboard settings
   * @param clock the time source
   */
  public DependencyTrackReader(
      final DashboardProperties properties, final Clock clock) {
    this.config = properties.dependencyTrack();
    this.clock = clock;
    this.httpClient = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
  }

  /**
   * Reads every active project and the 90-day portfolio trend.
   *
   * @return the report; never throws
   */
  public VulnerabilityReport read() {
    Instant now = clock.instant();
    if (!config.configured()) {
      return new VulnerabilityReport(Status.NOT_CONFIGURED,
          "DEPENDENCYTRACK_API_KEY is not set for the backend", List.of(), List.of(), now);
    }
    try {
      List<ProjectRow> projects =
          projects(get("/api/v1/project?pageSize=100&excludeInactive=true"));
      List<TrendPoint> trend = trend(get("/api/v1/metrics/portfolio/" + TREND_DAYS + "/days"));
      return new VulnerabilityReport(Status.OK, null, projects, trend, now);
    } catch (DependencyTrackException e) {
      LOG.warn("Dependency-Track panel unavailable: {}", e.getMessage());
      return new VulnerabilityReport(Status.UNAVAILABLE, e.getMessage(), List.of(), List.of(), now);
    }
  }

  private JsonNode get(final String path) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
        .timeout(config.timeout())
        .header("X-Api-Key", config.apiKey())
        .header("Accept", "application/json")
        .GET()
        .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new DependencyTrackException(
            "Dependency-Track answered %d for %s".formatted(response.statusCode(), pathOnly(path)));
      }
      return MAPPER.readTree(response.body());
    } catch (IOException | JacksonException e) {
      // The panel gets the exception's type only. Its message can carry part of a response body
      // or an internal address, so that goes to the log, not to the browser.
      LOG.warn("Dependency-Track GET {} failed: {}", pathOnly(path), e.getMessage());
      throw new DependencyTrackException("Dependency-Track could not be read (%s from %s)"
          .formatted(e.getClass().getSimpleName(), pathOnly(path)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DependencyTrackException("Interrupted reading Dependency-Track");
    }
  }

  private static String pathOnly(final String path) {
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  static List<ProjectRow> projects(final JsonNode body) {
    List<ProjectRow> rows = new ArrayList<>();
    if (body == null || !body.isArray()) {
      return rows;
    }
    for (JsonNode project : body) {
      JsonNode metrics = project.path("metrics");
      String version = project.path("version").asString(null);
      rows.add(new ProjectRow(
          project.path("name").asString(""),
          version == null || version.isBlank() ? null : version,
          metrics.path("components").asInt(0),
          metrics.path("critical").asInt(0),
          metrics.path("high").asInt(0),
          metrics.path("medium").asInt(0),
          metrics.path("low").asInt(0),
          metrics.path("unassigned").asInt(0),
          metrics.path("suppressed").asInt(0),
          metrics.path("inheritedRiskScore").asDouble(0),
          instant(project.path("lastBomImport"))));
    }
    rows.sort(Comparator.comparingInt(ProjectRow::critical).reversed()
        .thenComparing(Comparator.comparingInt(ProjectRow::high).reversed())
        .thenComparing(Comparator.comparingInt(ProjectRow::medium).reversed())
        .thenComparing(ProjectRow::name));
    return List.copyOf(rows);
  }

  /**
   * One point per day. Dependency-Track writes a portfolio row whenever its metrics change, so
   * a day can hold several; the latest one of each day is kept.
   */
  static List<TrendPoint> trend(final JsonNode body) {
    Map<LocalDate, JsonNode> latest = new TreeMap<>();
    Map<LocalDate, Instant> latestAt = new TreeMap<>();
    if (body == null || !body.isArray()) {
      return List.of();
    }
    for (JsonNode point : body) {
      Instant at = instant(point.path("lastOccurrence"));
      if (at == null) {
        at = instant(point.path("firstOccurrence"));
      }
      if (at == null) {
        continue;
      }
      LocalDate day = at.atZone(ZONE).toLocalDate();
      Instant held = latestAt.get(day);
      if (held == null || at.isAfter(held)) {
        latest.put(day, point);
        latestAt.put(day, at);
      }
    }
    List<TrendPoint> points = new ArrayList<>();
    latest.forEach((day, point) -> points.add(new TrendPoint(day,
        point.path("critical").asInt(0),
        point.path("high").asInt(0),
        point.path("medium").asInt(0),
        point.path("low").asInt(0))));
    return List.copyOf(points);
  }

  /** Dependency-Track sends epoch milliseconds; an ISO string is accepted too, defensively. */
  static Instant instant(final JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isNumber()) {
      return Instant.ofEpochMilli(node.asLong());
    }
    try {
      return Instant.parse(node.asString());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** A read that should be reported on the panel rather than thrown. */
  private static final class DependencyTrackException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DependencyTrackException(final String message) {
      super(message);
    }
  }
}
