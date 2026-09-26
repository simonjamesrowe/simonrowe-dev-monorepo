package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.AutoMergeState;
import com.simonrowe.factory.codereview.domain.ChangedFiles;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoMergeGatewayTest {

  private static final String PULL = "/repos/example/project/pulls/42";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final AtomicReference<String> graphQlBody = new AtomicReference<>();
  private final AtomicReference<String> graphQlResponse =
      new AtomicReference<>("{\"data\":{}}");

  private HttpServer server;
  private ExecutorService serverExecutor;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext(
        "/repos/",
        exchange -> {
          String key =
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI().getPath()
                  + (exchange.getRequestURI().getQuery() == null
                      ? ""
                      : "?" + exchange.getRequestURI().getQuery());
          requests.add(key);
          byte[] body = responses.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(responses.containsKey(key) ? 200 : 404, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/graphql",
        exchange -> {
          graphQlBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          requests.add("POST /graphql");
          byte[] body = graphQlResponse.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    serverExecutor.shutdownNow();
  }

  // --- reading the pull request ---------------------------------------------------------------

  @Test
  void readsEverythingTheDecisionNeedsFromOnePullRequestRead() {
    responses.put(
        "GET " + PULL,
        """
        {"node_id": "PR_node", "draft": false, "author_association": "OWNER",
         "changed_files": 3, "labels": [{"name": "backend"}, {"name": "no-auto-merge"}],
         "head": {"sha": "head-sha", "repo": {"full_name": "example/project"}},
         "base": {"repo": {"full_name": "example/project"}},
         "auto_merge": {"enabled_by": {"login": "simonrowe-software-factory[bot]",
                                       "type": "Bot"}}}
        """);

    AutoMergeState state = gateway().readState(pullRequest());

    assertThat(state)
        .isEqualTo(
            new AutoMergeState(
                "PR_node",
                "head-sha",
                false,
                false,
                "OWNER",
                List.of("backend", "no-auto-merge"),
                3,
                true,
                true));
  }

  @Test
  void personsArmIsArmedButNotByBot() {
    AutoMergeState state =
        AutoMergeGateway.toState(
            json(
                """
                {"node_id": "PR_node", "head": {"sha": "s", "repo": {"full_name": "a/b"}},
                 "base": {"repo": {"full_name": "a/b"}},
                 "auto_merge": {"enabled_by": {"login": "simonjamesrowe", "type": "User"}}}
                """));

    assertThat(state.autoMergeArmed()).isTrue();
    assertThat(state.autoMergeArmedByBot()).isFalse();
  }

  @Test
  void noAutoMergeIsNotArmed() {
    AutoMergeState state =
        AutoMergeGateway.toState(
            json(
                """
                {"node_id": "PR_node", "head": {"sha": "s", "repo": {"full_name": "a/b"}},
                 "base": {"repo": {"full_name": "a/b"}}, "auto_merge": null}
                """));

    assertThat(state.autoMergeArmed()).isFalse();
    assertThat(state.autoMergeArmedByBot()).isFalse();
  }

  @Test
  void forkIsCrossRepositoryAndSoIsDeletedFork() {
    assertThat(
            AutoMergeGateway.toState(
                    json(
                        """
                        {"node_id": "n", "head": {"sha": "s", "repo": {"full_name": "x/b"}},
                         "base": {"repo": {"full_name": "a/b"}}}
                        """))
                .crossRepository())
        .isTrue();
    // A deleted fork leaves head.repo null. A head nobody can name is not one this repo controls.
    assertThat(
            AutoMergeGateway.toState(
                    json(
                        """
                        {"node_id": "n", "head": {"sha": "s", "repo": null},
                         "base": {"repo": {"full_name": "a/b"}}}
                        """))
                .crossRepository())
        .isTrue();
  }

  @Test
  void missingFileCountCanNeverMatchListing() {
    AutoMergeState state =
        AutoMergeGateway.toState(
            json(
                """
                {"node_id": "n", "head": {"sha": "s", "repo": {"full_name": "a/b"}},
                 "base": {"repo": {"full_name": "a/b"}}}
                """));
    assertThat(state.changedFiles()).isEqualTo(-1);
  }

  @Test
  void responseWithNoNodeIdIsRefused() {
    assertThatThrownBy(
            () ->
                AutoMergeGateway.toState(
                    json("{\"head\": {\"sha\": \"s\"}, \"base\": {}}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("node_id");
  }

  // --- listing files ----------------------------------------------------------------------------

  @Test
  void listsBothSidesOfRenameAndIsCompleteWhenTheCountMatches() {
    responses.put(
        "GET " + PULL + "/files?per_page=100&page=1",
        """
        [{"filename": "docs/x.sh", "previous_filename": "scripts/x.sh", "status": "renamed"},
         {"filename": "backend/A.java", "status": "modified"}]
        """);

    ChangedFiles files = gateway().listFiles(pullRequest(), 2);

    assertThat(files.paths()).containsExactly("docs/x.sh", "scripts/x.sh", "backend/A.java");
    assertThat(files.complete()).isTrue();
  }

  @Test
  void pagesUntilShortPage() {
    responses.put("GET " + PULL + "/files?per_page=100&page=1", filesPage(0, 100));
    responses.put("GET " + PULL + "/files?per_page=100&page=2", filesPage(100, 5));

    ChangedFiles files = gateway().listFiles(pullRequest(), 105);

    assertThat(files.paths()).hasSize(105);
    assertThat(files.complete()).isTrue();
    assertThat(requests).hasSize(2);
  }

  /** GitHub stops at 3000 files. A listing that stops short cannot prove the rest are safe. */
  @Test
  void listingShortOfGitHubsOwnCountIsIncomplete() {
    responses.put("GET " + PULL + "/files?per_page=100&page=1", filesPage(0, 40));

    ChangedFiles files = gateway().listFiles(pullRequest(), 3500);

    assertThat(files.complete()).isFalse();
  }

  // --- arming and withdrawing -------------------------------------------------------------------

  @Test
  void armsSquashOnExactlyTheReviewedHead() {
    gateway().enable(pullRequest(), "PR_node", "head-sha");

    JsonNode sent = json(graphQlBody.get());
    assertThat(sent.path("query").asText()).contains("enablePullRequestAutoMerge");
    assertThat(sent.path("query").asText()).contains("mergeMethod: SQUASH");
    assertThat(sent.path("query").asText()).contains("expectedHeadOid: $expectedHeadOid");
    assertThat(sent.path("variables").path("pullRequestId").asText()).isEqualTo("PR_node");
    assertThat(sent.path("variables").path("expectedHeadOid").asText()).isEqualTo("head-sha");
  }

  @Test
  void withdraws() {
    gateway().disable(pullRequest(), "PR_node");

    JsonNode sent = json(graphQlBody.get());
    assertThat(sent.path("query").asText()).contains("disablePullRequestAutoMerge");
    assertThat(sent.path("variables").path("pullRequestId").asText()).isEqualTo("PR_node");
  }

  /** GraphQL answers 200 with an errors array, so the status code alone proves nothing. */
  @Test
  void rejectedMutationThrowsWithGitHubsMessage() {
    graphQlResponse.set(
        "{\"errors\":[{\"message\":\"Head sha didn't match expected head sha\"}]}");

    assertThatThrownBy(() -> gateway().enable(pullRequest(), "PR_node", "head-sha"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Head sha didn't match");
  }

  private static String filesPage(final int from, final int count) {
    return IntStream.range(from, from + count)
        .mapToObj(index -> "{\"filename\": \"backend/F" + index + ".java\"}")
        .collect(Collectors.joining(",", "[", "]"));
  }

  private JsonNode json(final String body) {
    return objectMapper.readTree(body);
  }

  private static PullRequestContext pullRequest() {
    return new PullRequestContext(
        "example", "project", 42, "Title", "Body", "https://github.com/example/project.git",
        "base-sha", "head-sha", null);
  }

  private AutoMergeGateway gateway() {
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "http://localhost:" + server.getAddress().getPort(),
                "test-token",
                "",
                "",
                "",
                Duration.ofSeconds(30)),
            new CodeReviewProperties.Agent(
                "claude", "sonnet", "medium", 12, Duration.ofMinutes(15),
                java.nio.file.Path.of("/tmp"), 2097152, 80, "v1"),
            new CodeReviewProperties.Api("token", null), "https://temporal.test");
    return new AutoMergeGateway(
        properties, new GitHubCredentials(properties, objectMapper), objectMapper);
  }
}
