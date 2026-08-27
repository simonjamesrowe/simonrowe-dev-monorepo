package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FactoryVersionClient} against a JDK {@link HttpServer} fake.
 *
 * <p>{@code mockwebserver3} is not a dependency of this project (confirmed by grepping
 * {@code backend/src/test} and {@code gradle/libs.versions.toml} before writing this class), and
 * the task's "no new dependencies" constraint rules out adding it, so the fake server here is
 * built on {@code com.sun.net.httpserver.HttpServer}, which ships in the JDK.
 */
class FactoryVersionClientTest {

  private static final String VERSION_PATH = "/api/version";
  private static final String UNREACHABLE_URL = "http://127.0.0.1:1/";

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private HttpServer startServer(final int status, final String body) throws IOException {
    final HttpServer newServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    newServer.createContext(VERSION_PATH, exchange -> respond(exchange, status, body));
    newServer.start();
    return newServer;
  }

  private static void respond(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(bytes);
    }
  }

  private static String baseUrl(final HttpServer target) {
    return "http://127.0.0.1:" + target.getAddress().getPort() + "/";
  }

  private FactoryVersionClient client(final String factoryUrl, final String deployerUrl) {
    return new FactoryVersionClient(factoryUrl, deployerUrl, Duration.ofSeconds(1), Duration.ZERO);
  }

  @Test
  void readsTheReportedVersion() throws IOException {
    server = startServer(200, """
        {"commit":"840c311abcdef0123456789abcdef0123456789a","shortCommit":"840c311",
         "commitSubject":"feat: deploy automatically","commitTime":"2026-08-26T14:02:11Z",
         "startedAt":"2026-08-24T09:15:03Z"}
        """);

    final List<ServiceVersion> versions = client(baseUrl(server), UNREACHABLE_URL).versions();

    final ServiceVersion factory = versions.get(0);
    assertThat(factory.name()).isEqualTo("software-factory");
    assertThat(factory.reachable()).isTrue();
    assertThat(factory.commit()).isEqualTo("840c311abcdef0123456789abcdef0123456789a");
    assertThat(factory.shortCommit()).isEqualTo("840c311");
    assertThat(factory.commitSubject()).isEqualTo("feat: deploy automatically");
  }

  @Test
  void reportsUnreachableRatherThanFailing() {
    final List<ServiceVersion> versions = client(UNREACHABLE_URL, UNREACHABLE_URL).versions();

    assertThat(versions).hasSize(2);
    assertThat(versions).allSatisfy(v -> assertThat(v.reachable()).isFalse());
    assertThat(versions).extracting(ServiceVersion::name)
        .containsExactly("software-factory", "deployer");
  }

  @Test
  void reportsUnreachableOnAnErrorStatus() throws IOException {
    server = startServer(503, "");

    final List<ServiceVersion> versions = client(baseUrl(server), UNREACHABLE_URL).versions();

    assertThat(versions.get(0).reachable()).isFalse();
  }

  @Test
  void reportsUnreachableOnMalformedJson() throws IOException {
    server = startServer(200, "not json");

    final List<ServiceVersion> versions = client(baseUrl(server), UNREACHABLE_URL).versions();

    assertThat(versions.get(0).reachable()).isFalse();
  }

  @Test
  void cachesWithinTheTtl() throws IOException {
    final AtomicInteger requestCount = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(VERSION_PATH, exchange -> {
      requestCount.incrementAndGet();
      respond(exchange, 200, "{\"commit\":\"840c311a\",\"shortCommit\":\"840c311\"}");
    });
    server.start();
    final FactoryVersionClient cached = new FactoryVersionClient(
        baseUrl(server), UNREACHABLE_URL, Duration.ofSeconds(1), Duration.ofMinutes(5));

    cached.versions();
    cached.versions();

    // One handler installed, two calls: the second must not have hit the network.
    assertThat(requestCount.get()).isEqualTo(1);
  }

  @Test
  void alwaysReturnsBothServicesInConfigurationOrder() {
    assertThat(client(UNREACHABLE_URL, UNREACHABLE_URL).versions())
        .extracting(ServiceVersion::name)
        .containsExactly("software-factory", "deployer");
  }
}
