package com.simonrowe.factory.deploy.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeployReportGatewayTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String COMMIT_COMMENTS_PATH =
      "/repos/simonjamesrowe/simonrowe-dev-monorepo/commits/" + SHA + "/comments";

  private HttpServer server;
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final Map<String, String> seenBodies = new ConcurrentHashMap<>();

  @BeforeEach
  void startServer() throws IOException {
    // Explicit loopback rather than the wildcard address, and a catch-all context below.
    // This module's suite starts a lot of these stub servers on ephemeral ports; a request
    // that lands somewhere unexpected otherwise surfaces as a bare 404 from the JDK's
    // default handler, which says nothing about what was actually asked for.
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/repos/", this::respond);
    // Anything that is not a /repos/ call is a bug in the test or in the gateway's path
    // building. Answer it with a status no real GitHub route returns, and put the path in
    // the body, so the failure message identifies itself.
    server.createContext(
        "/",
        exchange -> {
          byte[] body =
              ("unexpected request: "
                      + exchange.getRequestMethod()
                      + " "
                      + exchange.getRequestURI())
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(599, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  private void respond(final HttpExchange exchange) throws IOException {
    String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
    seenBodies.put(
        key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    byte[] body = responses.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
    // No keep-alive: the client is discarded after each test, and a pooled connection to a
    // port the OS is free to hand to the next stub server is a source of cross-test
    // flakiness rather than of speed.
    exchange.getResponseHeaders().add("Connection", "close");
    exchange.sendResponseHeaders(statuses.getOrDefault(key, 201), body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private DeployReportGateway gateway() {
    CodeReviewProperties codeReviewProperties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "http://localhost:" + server.getAddress().getPort(),
                "static-token",
                "webhook-secret",
                "",
                "",
                Duration.ofSeconds(5)),
            null,
            new CodeReviewProperties.Api("", null),
            "https://temporal.test");
    DeployProperties properties =
        new DeployProperties(
            true, false, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, false);
    GitHubCredentials credentials = new GitHubCredentials(codeReviewProperties, new ObjectMapper());
    return new DeployReportGateway(
        codeReviewProperties, properties, credentials, new ObjectMapper());
  }

  @Test
  void commentsOnCommitAndReturnsItsUrl() {
    responses.put(
        "POST " + COMMIT_COMMENTS_PATH,
        "{\"html_url\": \"https://github.com/o/r/commit/x#commitcomment-1\"}");

    String url = gateway().commentOnCommit(SHA, "the site is up, on the previous version", 1L);

    assertThat(url).isEqualTo("https://github.com/o/r/commit/x#commitcomment-1");
    assertThat(seenBodies.get("POST " + COMMIT_COMMENTS_PATH)).contains("previous version");
  }

  @Test
  void throwsOnNonSuccessStatusRatherThanReportingNothingQuietly() {
    // Loud, deliberately: the caller catches this and records that it could not comment. Silently
    // returning a null URL would make a lost report indistinguishable from a posted one.
    statuses.put("POST " + COMMIT_COMMENTS_PATH, 500);

    assertThatThrownBy(() -> gateway().commentOnCommit(SHA, "b", 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("500");
  }
}
