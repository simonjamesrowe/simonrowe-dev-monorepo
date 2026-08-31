package com.simonrowe.factory.logwatch.loki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.Severity;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the Loki read path against a real HTTP server, as {@code DependencyTrackClientTest}
 * does for Dependency-Track. */
class LokiClientTest {

  private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-02T00:00:00Z");

  private HttpServer server;
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final Map<String, String> seenQueries = new ConcurrentHashMap<>();
  private final Map<String, String> seenAuth = new ConcurrentHashMap<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/loki/api/v1/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          seenQueries.put(path, String.valueOf(exchange.getRequestURI().getQuery()));
          seenAuth.put(
              path,
              String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] body =
              responses.getOrDefault(path, "{\"status\":\"success\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  /**
   * The endpoint is configured as the <em>push</em> URL, ending in {@code /push}, exactly as
   * {@code GRAFANA_CLOUD_LOKI_ENDPOINT} does in {@code .env}.
   */
  private LokiClient client() {
    return new LokiClient(properties("/push"), new ObjectMapper());
  }

  private LogWatchProperties properties(final String suffix) {
    return new LogWatchProperties(
        true, 2, 5, null, 5000, 3,
        new LogWatchProperties.Loki(
            "http://localhost:" + server.getAddress().getPort() + "/loki/api/v1" + suffix,
            "1539009",
            "test-key",
            Duration.ofSeconds(5)),
        null);
  }

  @Test
  @DisplayName("the trailing /push is stripped, so the query path is not doubled")
  void stripsThePushSuffixFromTheQueryBase() {
    responses.put("/loki/api/v1/query_range", emptyResult());

    client().linesIn(FROM, TO, 100);

    // If queryBase() did not strip /push, this would be /loki/api/v1/push/query_range - or,
    // if a caller appended /api/v1 to the raw value, /loki/api/v1/api/v1/... Both return a bare
    // `404 page not found` with no JSON and no hint what is wrong.
    assertThat(seenQueries).containsKey("/loki/api/v1/query_range");
  }

  @Test
  @DisplayName("an endpoint without /push is left alone")
  void toleratesAnEndpointWithoutPush() {
    responses.put("/loki/api/v1/query_range", emptyResult());

    new LokiClient(properties(""), new ObjectMapper()).linesIn(FROM, TO, 100);

    assertThat(seenQueries).containsKey("/loki/api/v1/query_range");
  }

  @Test
  @DisplayName("timestamps are sent in NANOseconds")
  void sendsNanosecondTimestamps() {
    responses.put("/loki/api/v1/query_range", emptyResult());

    client().linesIn(FROM, TO, 100);

    // Seconds are accepted by Loki and silently return an empty result for a window about fifty
    // years wide in the wrong place - an empty success, the one shape this module must never
    // read as clean. 2026-09-01T00:00:00Z is 1788220800 seconds.
    assertThat(seenQueries.get("/loki/api/v1/query_range"))
        .contains("start=1788220800000000000")
        .contains("limit=100");
  }

  @Test
  void authenticatesWithTheTenantIdAndKey() {
    responses.put("/loki/api/v1/query_range", emptyResult());

    client().linesIn(FROM, TO, 100);

    String expected =
        "Basic "
            + java.util.Base64.getEncoder()
                .encodeToString("1539009:test-key".getBytes(StandardCharsets.UTF_8));
    assertThat(seenAuth.get("/loki/api/v1/query_range")).isEqualTo(expected);
  }

  @Test
  @DisplayName("lines are classified, and unclassifiable ones are dropped rather than defaulted")
  void parsesAndFiltersLines() {
    responses.put(
        "/loki/api/v1/query_range",
        """
        {"status":"success","data":{"result":[
          {"stream":{"container":"backend"},"values":[
            ["1788220800000000000","level=error msg=\\"boom\\""],
            ["1788220860000000000","MCP feature registered: models"],
            ["1788220920000000000","level=warn msg=\\"odd\\""]
          ]}
        ]}}
        """);

    List<LogLine> lines = client().linesIn(FROM, TO, 100);

    assertThat(lines).hasSize(2);
    assertThat(lines).extracting(LogLine::severity)
        .containsExactly(Severity.ERROR, Severity.WARN);
    assertThat(lines).extracting(LogLine::container).containsOnly("backend");
    assertThat(lines.getFirst().timestamp()).isEqualTo(FROM);
  }

  @Test
  @DisplayName("an empty success parses to no lines and does NOT raise")
  void emptySuccessIsNotAnError() {
    responses.put("/loki/api/v1/query_range", emptyResult());

    // The whole reason SourceHealthChecker exists: this response is indistinguishable from a
    // quiet stack, and the client must not pretend otherwise by throwing.
    assertThat(client().linesIn(FROM, TO, 100)).isEmpty();
  }

  @Test
  void countsDistinctContainers() {
    responses.put(
        "/loki/api/v1/label/container/values",
        "{\"status\":\"success\",\"data\":[\"backend\",\"nginx\",\"alloy\"]}");

    assertThat(client().distinctContainers(FROM, TO)).isEqualTo(3);
  }

  @Test
  @DisplayName("a label response with no data at all counts zero rather than raising")
  void handlesAbsentLabelData() {
    responses.put("/loki/api/v1/label/container/values", "{\"status\":\"success\"}");

    assertThat(client().distinctContainers(FROM, TO)).isZero();
  }

  @Test
  @DisplayName("a 404 says the query base is probably doubled, because the body never will")
  void explainsA404() {
    responses.put("/loki/api/v1/query_range", "404 page not found");
    statuses.put("/loki/api/v1/query_range", 404);

    assertThatThrownBy(() -> client().linesIn(FROM, TO, 100))
        .isInstanceOf(LokiException.class)
        .hasMessageContaining("query base is doubled");
  }

  @Test
  void raisesOnAnyOtherNonSuccess() {
    responses.put("/loki/api/v1/query_range", "unauthorized");
    statuses.put("/loki/api/v1/query_range", 401);

    assertThatThrownBy(() -> client().linesIn(FROM, TO, 100))
        .isInstanceOf(LokiException.class)
        .hasMessageContaining("401");
  }

  @Test
  @DisplayName("an unconfigured client refuses rather than calling a blank URL")
  void refusesWhenUnconfigured() {
    LokiClient unconfigured =
        new LokiClient(
            new LogWatchProperties(true, 2, 5, null, 5000, 3, null, null), new ObjectMapper());

    assertThat(unconfigured.configured()).isFalse();
    assertThatThrownBy(() -> unconfigured.linesIn(FROM, TO, 100))
        .isInstanceOf(LokiException.class)
        .hasMessageContaining("not configured");
  }

  private static String emptyResult() {
    return "{\"status\":\"success\",\"data\":{\"result\":[]}}";
  }
}
