package com.simonrowe.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V068CreateUptimeHourIndexes;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Runs the real prober against a local stub and a real Mongo, so the {@code $inc} upsert, the
 * hour bucketing and the TTL index are all exercised as production runs them.
 */
class UptimeProberIntegrationTest extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-26T14:37:12Z");

  @Autowired
  private MongoTemplate mongoTemplate;

  private HttpServer server;
  private String base;

  @BeforeEach
  void setUp() throws IOException {
    mongoTemplate.getCollection(UptimeHour.COLLECTION).drop();
    V068CreateUptimeHourIndexes.createIndexes(mongoTemplate);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ok", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.createContext("/broken", exchange -> {
      exchange.sendResponseHeaders(502, -1);
      exchange.close();
    });
    server.createContext("/moved", exchange -> {
      exchange.getResponseHeaders().add("Location", "/ok");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/slow", exchange -> {
      try {
        TimeUnit.SECONDS.sleep(3);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    mongoTemplate.getCollection(UptimeHour.COLLECTION).drop();
  }

  private UptimeProber prober(final List<DashboardProperties.Target> targets) {
    DashboardProperties properties = new DashboardProperties(
        new DashboardProperties.Uptime(true, Duration.ofSeconds(1), targets), null, null);
    return new UptimeProber(properties, mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void countsEveryProbeIntoOneDocumentPerTargetPerHour() {
    UptimeProber prober = prober(List.of(
        new DashboardProperties.Target("www", base + "/ok"),
        new DashboardProperties.Target("api", base + "/broken")));

    prober.probeAll();
    prober.probeAll();

    UptimeHour www = mongoTemplate.findById(
        UptimeHour.idFor("www", Instant.parse("2026-11-26T14:00:00Z")), UptimeHour.class);
    UptimeHour api = mongoTemplate.findById(
        UptimeHour.idFor("api", Instant.parse("2026-11-26T14:00:00Z")), UptimeHour.class);
    assertThat(www).isNotNull();
    assertThat(www.probes()).isEqualTo(2);
    assertThat(www.failures()).isZero();
    assertThat(www.lastFailure()).isNull();
    assertThat(api.probes()).isEqualTo(2);
    assertThat(api.failures()).isEqualTo(2);
    assertThat(api.lastFailure()).isEqualTo("HTTP 502");
    assertThat(api.lastFailureAt()).isEqualTo(NOW);
    assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(),
        UptimeHour.class)).isEqualTo(2);
  }

  @Test
  void redirectTimeoutAndRefusedConnectionAllFail() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    List<UptimeProber.ProbeResult> results = prober(List.of(
        new DashboardProperties.Target("moved", base + "/moved"),
        new DashboardProperties.Target("slow", base + "/slow"),
        new DashboardProperties.Target("refused", "http://127.0.0.1:" + closedPort + "/")))
        .probeAll();

    assertThat(results).extracting(UptimeProber.ProbeResult::failure)
        .containsExactly("HTTP 302", "timed out", "unreachable");
  }

  @Test
  void theChangeUnitExpiresHoursAfter120Days() {
    Document index = mongoTemplate.getCollection(UptimeHour.COLLECTION).listIndexes()
        .into(new java.util.ArrayList<>()).stream()
        .filter(i -> "idx_uptime_hour_start_ttl".equals(i.getString("name")))
        .findFirst()
        .orElseThrow();

    assertThat(index.get("key", Document.class).getInteger("hourStart")).isEqualTo(1);
    assertThat(((Number) index.get("expireAfterSeconds")).longValue())
        .isEqualTo(Duration.ofDays(120).toSeconds());
  }
}
