package com.simonrowe.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.dashboard.VulnerabilityReport.ProjectRow;
import com.simonrowe.dashboard.VulnerabilityReport.Status;
import com.simonrowe.dashboard.VulnerabilityReport.TrendPoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests {@link DependencyTrackReader} against a stub shaped like Dependency-Track 5.0.3. */
class DependencyTrackReaderTest {

  private static final String KEY = "odt_test-key";
  // 2026-11-25T09:00Z and 2026-11-25T21:00Z: two rows on one day, the later one kept.
  private static final String PROJECTS = """
      [
        {"name":"simonrowe-dev/frontend","version":"","uuid":"f",
         "lastBomImport":1795597200000,
         "metrics":{"components":642,"critical":0,"high":0,"medium":2,"low":1,
                    "unassigned":0,"suppressed":3,"inheritedRiskScore":7.0}},
        {"name":"simonrowe-dev/backend-image","version":"latest","uuid":"b",
         "metrics":{"components":99,"critical":0,"high":4,"medium":41,"low":88,
                    "inheritedRiskScore":325.0}},
        {"name":"no-metrics-yet","uuid":"n"}
      ]
      """;
  private static final String PORTFOLIO = """
      [
        {"firstOccurrence":1795597200000,"lastOccurrence":1795597200000,
         "critical":1,"high":9,"medium":40,"low":80},
        {"firstOccurrence":1795597200000,"lastOccurrence":1795640400000,
         "critical":0,"high":7,"medium":43,"low":90},
        {"firstOccurrence":1795683600000,"lastOccurrence":1795683600000,
         "critical":0,"high":6,"medium":43,"low":90}
      ]
      """;

  private HttpServer server;
  private final List<String> keys = new CopyOnWriteArrayList<>();
  private volatile int status = 200;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/project", exchange -> respond(exchange, PROJECTS));
    server.createContext("/api/v1/metrics/portfolio/90/days",
        exchange -> respond(exchange, PORTFOLIO));
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void respond(final HttpExchange exchange, final String body) throws IOException {
    keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Api-Key")));
    byte[] bytes = (status == 200 ? body : "{}").getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private DependencyTrackReader reader(final String key) {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    DashboardProperties properties = new DashboardProperties(null,
        new DashboardProperties.DependencyTrack(base + "/", key, Duration.ofSeconds(2)), null);
    return new DependencyTrackReader(properties,
        Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void readsEveryProjectMostSevereFirstWithTheKey() {
    VulnerabilityReport report = reader(KEY).read();

    assertThat(report.status()).isEqualTo(Status.OK);
    assertThat(report.detail()).isNull();
    assertThat(report.projects()).extracting(ProjectRow::name)
        .containsExactly("simonrowe-dev/backend-image", "simonrowe-dev/frontend", "no-metrics-yet");
    ProjectRow frontend = report.projects().get(1);
    assertThat(frontend.version()).isNull();
    assertThat(frontend.components()).isEqualTo(642);
    assertThat(frontend.medium()).isEqualTo(2);
    assertThat(frontend.suppressed()).isEqualTo(3);
    assertThat(frontend.lastBomImport()).isEqualTo(Instant.ofEpochMilli(1795597200000L));
    assertThat(report.projects().get(0).riskScore()).isEqualTo(325.0);
    assertThat(keys).containsOnly(KEY);
  }

  @Test
  void keepsTheLatestPortfolioRowOfEachDay() {
    List<TrendPoint> trend = reader(KEY).read().trend();

    assertThat(trend).containsExactly(
        new TrendPoint(LocalDate.of(2026, 11, 25), 0, 7, 43, 90),
        new TrendPoint(LocalDate.of(2026, 11, 26), 0, 6, 43, 90));
  }

  @Test
  void rejectedKeyIsReportedWithTheStatusNotThrown() {
    status = 401;

    VulnerabilityReport report = reader(KEY).read();

    assertThat(report.status()).isEqualTo(Status.UNAVAILABLE);
    assertThat(report.detail()).isEqualTo("Dependency-Track answered 401 for /api/v1/project");
    assertThat(report.projects()).isEmpty();
  }

  @Test
  void withNoKeyItAsksNothing() {
    VulnerabilityReport report = reader("  ").read();

    assertThat(report.status()).isEqualTo(Status.NOT_CONFIGURED);
    assertThat(keys).isEmpty();
  }

  @Test
  void anUnreachableServerIsUnavailable() {
    server.stop(0);

    VulnerabilityReport report = reader(KEY).read();

    assertThat(report.status()).isEqualTo(Status.UNAVAILABLE);
    assertThat(report.detail()).startsWith("Dependency-Track could not be read (")
        .endsWith(" from /api/v1/project)");
  }

  @Test
  void unparseableBodyNamesTheErrorTypeButNeverEchoesTheBody() {
    server.removeContext("/api/v1/project");
    server.createContext("/api/v1/project", exchange -> {
      byte[] bytes = "<html>internal-host-10.0.0.7 secret-ish</html>"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });

    VulnerabilityReport report = reader(KEY).read();

    assertThat(report.status()).isEqualTo(Status.UNAVAILABLE);
    assertThat(report.detail()).doesNotContain("internal-host").doesNotContain("secret");
  }
}
