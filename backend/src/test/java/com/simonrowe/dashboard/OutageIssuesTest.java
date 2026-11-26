package com.simonrowe.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.dashboard.UptimeReport.Incident;
import com.simonrowe.dashboard.UptimeReport.Incidents;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests {@link OutageIssues} against a stub of GitHub's issue listing. */
class OutageIssuesTest {

  private static final String REPO = "simonjamesrowe/simonrowe-dev-monorepo";
  private static final String LISTING = """
      [
        {"number":306,"title":"Production heartbeat failing: logs: NOTHING received",
         "created_at":"2026-11-18T01:29:17Z","closed_at":"2026-11-18T17:31:55Z",
         "html_url":"https://github.com/x/y/issues/306"},
        {"number":307,"title":"A pull request with the label","created_at":"2026-11-19T00:00:00Z",
         "html_url":"https://github.com/x/y/pull/307","pull_request":{"url":"u"}},
        {"number":308,"title":"Still open","created_at":"2026-11-20T00:00:00Z",
         "closed_at":null,"html_url":"https://github.com/x/y/issues/308"}
      ]
      """;

  private HttpServer server;
  private final List<String> rawPaths = new CopyOnWriteArrayList<>();
  private final List<String> rawQueries = new CopyOnWriteArrayList<>();
  private final List<String> validators = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-26T12:00:00Z"));

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/repos/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(final HttpExchange exchange) throws IOException {
    rawPaths.add(exchange.getRequestURI().getRawPath());
    rawQueries.add(exchange.getRequestURI().getRawQuery());
    String validator = exchange.getRequestHeaders().getFirst("If-None-Match");
    validators.add(validator == null ? "" : validator);
    if ("\"v1\"".equals(validator)) {
      exchange.sendResponseHeaders(304, -1);
      exchange.close();
      return;
    }
    byte[] bytes = (status == 200 ? LISTING : "{}").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("ETag", "\"v1\"");
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private OutageIssues issues() {
    DashboardProperties properties = new DashboardProperties(null, null,
        new DashboardProperties.Alerts("http://127.0.0.1:" + server.getAddress().getPort(),
            REPO, "prod-down", Duration.ofMinutes(5)));
    return new OutageIssues(properties, clock);
  }

  @Test
  void listsOutageIssuesWithoutPullRequests() {
    Incidents incidents = issues().recent();

    assertThat(incidents.available()).isTrue();
    assertThat(incidents.items()).extracting(Incident::number).containsExactly(306, 308);
    assertThat(incidents.items().get(0).closedAt())
        .isEqualTo(Instant.parse("2026-11-18T17:31:55Z"));
    assertThat(incidents.items().get(1).closedAt()).isNull();
    // Owner and name stay two path segments; a %2F here is a 404 from GitHub.
    assertThat(rawPaths).containsExactly("/repos/" + REPO + "/issues");
    assertThat(rawQueries.getFirst()).contains("labels=prod-down").contains("state=all");
  }

  @Test
  void reusesTheListWithinTheCacheWindowThenRevalidatesWithTheEtag() {
    OutageIssues issues = issues();
    issues.recent();
    clock.advance(Duration.ofMinutes(4));
    issues.recent();
    assertThat(validators).hasSize(1);

    clock.advance(Duration.ofMinutes(2));
    Incidents revalidated = issues.recent();

    assertThat(validators).containsExactly("", "\"v1\"");
    assertThat(revalidated.items()).hasSize(2);
  }

  @Test
  void servesTheLastListWhenGitHubFailsAndSaysUnavailableOnlyWithNothingCached() {
    status = 500;
    assertThat(issues().recent().available()).isFalse();

    status = 200;
    OutageIssues issues = issues();
    issues.recent();
    server.stop(0);
    clock.advance(Duration.ofMinutes(10));

    Incidents stale = issues.recent();
    assertThat(stale.available()).isTrue();
    assertThat(stale.items()).hasSize(2);
  }

  /** A clock the test moves by hand. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(final Instant start) {
      this.now = start;
    }

    void advance(final Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
