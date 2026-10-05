package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link GitHubCommitHistory} against a JDK {@link HttpServer} standing in for the GitHub
 * API, the same fake {@code FactoryVersionClientTest} uses.
 */
class GitHubCommitHistoryTest {

  private static final String REPO = "simonjamesrowe/simonrowe-dev-monorepo";
  private static final String SHA = "840c311abcdef0123456789abcdef0123456789a";
  private static final String LISTING = """
      [
        {"sha":"840c311abcdef0123456789abcdef0123456789a",
         "commit":{"message":"feat: deploy automatically (#116)\\n\\nA body\\nover two lines\\n",
                   "author":{"date":"2026-08-20T10:00:00Z"},
                   "committer":{"date":"2026-08-26T14:02:11Z"}}},
        {"sha":"39e0f7aabcdef0123456789abcdef0123456789a",
         "commit":{"message":"docs: no body","committer":{"date":"2026-08-25T09:00:00Z"}}},
        {"sha":"0000000abcdef0123456789abcdef0123456789a","commit":{"message":"no date"}}
      ]
      """;

  private HttpServer server;
  private final List<String> ifNoneMatch = new CopyOnWriteArrayList<>();
  private volatile int listingStatus = 200;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/repos/" + REPO + "/commits", this::handle);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private void handle(final HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.endsWith("/commits")) {
      String validator = exchange.getRequestHeaders().getFirst("If-None-Match");
      ifNoneMatch.add(validator == null ? "" : validator);
      if ("\"v1\"".equals(validator)) {
        exchange.sendResponseHeaders(304, -1);
        exchange.close();
        return;
      }
      exchange.getResponseHeaders().add("ETag", "\"v1\"");
      respond(exchange, listingStatus, listingStatus == 200 ? LISTING : "{}");
      return;
    }
    respond(exchange, 200, """
        {"sha":"%s","files":[{"filename":"backend/build.gradle.kts"},
                             {"filename":".github/workflows/ci.yml"},{"filename":""}]}
        """.formatted(SHA));
  }

  private static void respond(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private GitHubCommitHistory history() {
    return new GitHubCommitHistory(
        "http://127.0.0.1:" + server.getAddress().getPort(), REPO, "main", 50,
        Duration.ofSeconds(2));
  }

  @Test
  void readsSubjectBodyAndTheCommitterDate() {
    List<MainCommit> commits = history().recent();

    assertThat(commits).extracting(MainCommit::sha).hasSize(2);
    MainCommit first = commits.get(0);
    assertThat(first.subject()).isEqualTo("feat: deploy automatically (#116)");
    assertThat(first.body()).isEqualTo("A body\nover two lines");
    // The committer date: on a squash merge that is when the change reached main.
    assertThat(first.commitTime()).isEqualTo(Instant.parse("2026-08-26T14:02:11Z"));
    assertThat(first.filesChanged()).isEmpty();
    assertThat(commits.get(1).body()).isEmpty();
  }

  @Test
  void revalidatesWithTheEtagAndReusesTheListingOnNotModified() {
    // GitHub does not count a 304 against the anonymous rate limit, which is what makes polling
    // every few minutes affordable.
    GitHubCommitHistory history = history();
    List<MainCommit> first = history.recent();

    List<MainCommit> second = history.recent();

    assertThat(ifNoneMatch).containsExactly("", "\"v1\"");
    assertThat(second).isEqualTo(first);
  }

  @Test
  void failsRatherThanReportingAnEmptyHistoryWhenGitHubRefuses() {
    // An empty list would read as "main has no commits", not "try again later".
    listingStatus = 403;

    assertThatThrownBy(() -> history().recent()).hasMessageContaining("403");
  }

  @Test
  void readsTheFilesOfOneCommit() {
    assertThat(history().filesChanged(SHA))
        .containsExactly("backend/build.gradle.kts", ".github/workflows/ci.yml");
  }
}
