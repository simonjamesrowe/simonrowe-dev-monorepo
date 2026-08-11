package com.simonrowe.factory.cvefix.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CveFixPrGatewayTest {

  private static final String PULLS_PATH =
      "/repos/simonjamesrowe/simonrowe-dev-monorepo/pulls";
  private static final String COMMENTS_PATH =
      "/repos/simonjamesrowe/simonrowe-dev-monorepo/issues/7/comments";

  private HttpServer server;
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final Map<String, String> seenQueries = new ConcurrentHashMap<>();
  private final Map<String, String> seenBodies = new ConcurrentHashMap<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/repos/",
        exchange -> {
          String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          String query = exchange.getRequestURI().getRawQuery();
          if (query != null) {
            seenQueries.put(key, query);
          }
          seenBodies.put(
              key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = responses.getOrDefault(key, "[]").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private CveFixPrGateway gateway() {
    CodeReviewProperties codeReviewProperties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "http://localhost:" + server.getAddress().getPort(),
                "static-token",
                "webhook-secret",
                "",
                "",
                Duration.ofSeconds(5)),
            new CodeReviewProperties.Agent(
                "claude",
                "sonnet",
                "medium",
                12,
                Duration.ofMinutes(15),
                Path.of("/tmp/cvefix-test"),
                2_097_152,
                80,
                "v1"),
            new CodeReviewProperties.Api(""));
    CveFixProperties properties =
        new CveFixProperties(
            true,
            "simonjamesrowe",
            "simonrowe-dev-monorepo",
            "chore/dependency-cve-fixes",
            "main",
            null,
            null,
            null,
            null,
            null,
            null);
    GitHubCredentials credentials = new GitHubCredentials(codeReviewProperties, new ObjectMapper());
    return new CveFixPrGateway(codeReviewProperties, properties, credentials, new ObjectMapper());
  }

  @Test
  void findOpenReturnsEmptyWhenNoPullRequestIsOpen() {
    responses.put("GET " + PULLS_PATH, "[]");

    Optional<CveFixPrGateway.OpenPullRequest> found = gateway().findOpen();

    assertThat(found).isEmpty();
  }

  @Test
  void findOpenReturnsTheNumberUrlAndHeadSha() {
    responses.put(
        "GET " + PULLS_PATH,
        """
        [{"number":7,"html_url":"https://github.com/o/r/pull/7","head":{"sha":"abc"}}]
        """);

    Optional<CveFixPrGateway.OpenPullRequest> found = gateway().findOpen();

    assertThat(found)
        .contains(new CveFixPrGateway.OpenPullRequest(7, "https://github.com/o/r/pull/7", "abc"));
  }

  @Test
  void findOpenThrowsOnAnErrorResponseRatherThanReportingNoneOpen() {
    statuses.put("GET " + PULLS_PATH, 503);

    assertThatThrownBy(() -> gateway().findOpen())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("503");
  }

  @Test
  void findOpenQueriesTheConfiguredBranchAsTheHeadFilterUrlEncoded() {
    responses.put("GET " + PULLS_PATH, "[]");

    gateway().findOpen();

    assertThat(seenQueries.get("GET " + PULLS_PATH))
        .isEqualTo("head=simonjamesrowe:chore%2Fdependency-cve-fixes&state=open");
  }

  @Test
  void openSendsDraftFalseExplicitly() {
    responses.put(
        "POST " + PULLS_PATH,
        """
        {"number":8,"html_url":"https://github.com/o/r/pull/8","head":{"sha":"def"}}
        """);

    gateway().open("chore: bump vulnerable dependencies", "Body");

    assertThat(seenBodies.get("POST " + PULLS_PATH)).contains("\"draft\":false");
  }

  @Test
  void openFallsBackToFindingTheExistingPullRequestOn422() {
    statuses.put("POST " + PULLS_PATH, 422);
    responses.put(
        "GET " + PULLS_PATH,
        """
        [{"number":9,"html_url":"https://github.com/o/r/pull/9","head":{"sha":"ghi"}}]
        """);

    CveFixPrGateway.OpenPullRequest result = gateway().open("title", "body");

    assertThat(result)
        .isEqualTo(new CveFixPrGateway.OpenPullRequest(9, "https://github.com/o/r/pull/9", "ghi"));
  }

  @Test
  void commentPostsToTheIssueCommentsEndpoint() {
    gateway().comment(7, "CI is green, ready for review.");

    assertThat(seenBodies.get("POST " + COMMENTS_PATH))
        .contains("\"body\":\"CI is green, ready for review.\"");
  }
}
