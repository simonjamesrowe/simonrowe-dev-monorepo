package com.simonrowe.factory.logwatch.loki;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The direct tier of the source-health check.
 *
 * <p>Every failure path here must report "unreachable" rather than throwing: this is an optional
 * signal on the critical path of a scan, and losing it must degrade the check to the coverage
 * tier, never fail the run.
 */
class AlloyHealthClientTest {

  private HttpServer server;
  private final AtomicReference<String> body = new AtomicReference<>("[]");
  private final AtomicInteger status = new AtomicInteger(200);

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/api/v0/web/components",
        exchange -> {
          byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private AlloyHealthClient client(final String baseUrl) {
    return new AlloyHealthClient(
        new LogWatchProperties(
            true, 2, 5, null, 5000, 3, null,
            new LogWatchProperties.Alloy(baseUrl, Duration.ofSeconds(2))),
        new ObjectMapper());
  }

  private AlloyHealthClient client() {
    return client("http://localhost:" + server.getAddress().getPort());
  }

  @Test
  @DisplayName("a healthy loki.write component reports reachable with no error")
  void healthyComponent() {
    body.set(
        "[{\"localID\":\"loki.write.grafana_cloud\",\"health\":{\"state\":\"healthy\"}}]");

    AlloyHealthClient.WriteHealth health = client().writeHealth();

    assertThat(health.reachable()).isTrue();
    assertThat(health.error()).isEmpty();
  }

  /** The August 2026 outage, as Alloy actually reported it. */
  @Test
  @DisplayName("an unhealthy component surfaces the message verbatim, 429 and all")
  void unhealthyComponentCarriesTheRealMessage() {
    body.set(
        """
        [{"localID":"loki.write.grafana_cloud","health":{"state":"unhealthy",
          "message":"ingestion rate limit exceeded for user 1539009 (limit: 0 bytes/sec)"}}]
        """);

    AlloyHealthClient.WriteHealth health = client().writeHealth();

    assertThat(health.reachable()).isTrue();
    // "no logs found" sends an operator to check credentials that are fine. This sends them to
    // the billing page.
    assertThat(health.error()).hasValueSatisfying(m -> assertThat(m).contains("0 bytes/sec"));
  }

  @Test
  @DisplayName("components other than loki.write are ignored")
  void ignoresUnrelatedComponents() {
    body.set(
        """
        [{"localID":"loki.source.docker.default","health":{"state":"unhealthy",
          "message":"container no longer exists"}},
         {"localID":"loki.write.grafana_cloud","health":{"state":"healthy"}}]
        """);

    assertThat(client().writeHealth().error()).isEmpty();
  }

  @Test
  void nonSuccessStatusIsUnreachableRatherThanAnError() {
    status.set(500);

    AlloyHealthClient.WriteHealth health = client().writeHealth();

    assertThat(health.reachable()).isFalse();
    assertThat(health.error()).isEmpty();
  }

  @Test
  @DisplayName("malformed JSON degrades to unreachable, it does not fail the scan")
  void malformedJsonIsUnreachable() {
    body.set("{ this is not json");

    assertThat(client().writeHealth().reachable()).isFalse();
  }

  @Test
  @DisplayName("a connection failure degrades to unreachable, it does not fail the scan")
  void connectionFailureIsUnreachable() {
    // Nothing is listening on this port; Alloy publishes no host port in production either, so
    // this is the realistic case whenever the module runs anywhere but the compose network.
    AlloyHealthClient.WriteHealth health = client("http://localhost:1").writeHealth();

    assertThat(health.reachable()).isFalse();
    assertThat(health.error()).isEmpty();
  }
}
