package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewPhase;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The check run is the only review signal a merge ruleset can read, so its wire shape matters. */
class CheckRunGatewayTest {

  private static final String CHECK_RUNS = "/repos/example/project/check-runs";
  private static final String CHECK_RUN_7 = "/repos/example/project/check-runs/7";

  private static final ReviewFinding WARNING =
      new ReviewFinding(Severity.WARNING, "src/App.java", 12, "Bad", "Because.", "Fix it.");
  private static final ReviewFinding CRITICAL =
      new ReviewFinding(Severity.CRITICAL, "src/App.java", 20, "Worse", "Because.", "Fix it.");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final Map<String, String> sentBodies = new ConcurrentHashMap<>();
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();

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
          String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          requests.add(key);
          sentBodies.put(
              key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              responses.getOrDefault(key, "{\"id\": 7}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), body.length);
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

  @Test
  void openingCreatesAnInProgressCheckNamedForTheRulesetToRequire() {
    String id = gateway().open(pullRequest(), "code-review-abc");

    assertThat(id).isEqualTo("7");
    assertThat(requests).containsExactly("POST " + CHECK_RUNS);

    JsonNode payload = parse(sentBodies.get("POST " + CHECK_RUNS));
    assertThat(payload.path("name").asText()).isEqualTo("Code Review");
    assertThat(payload.path("head_sha").asText()).isEqualTo("head-sha");
    assertThat(payload.path("status").asText()).isEqualTo("in_progress");
    assertThat(payload.path("started_at").asText()).isEqualTo("2026-08-27T12:00:00Z");
    assertThat(payload.has("conclusion")).isFalse();
  }

  @Test
  void openingLinksTheWorkflowHistorySoFailuresCanBeDiagnosedFromThePullRequest() {
    gateway().open(pullRequest(), "code-review-abc");

    assertThat(parse(sentBodies.get("POST " + CHECK_RUNS)).path("details_url").asText())
        .isEqualTo("https://temporal.test/namespaces/default/workflows/code-review-abc");
  }

  /** An unset Temporal base URL must cost the reader the link, never the conclusion. */
  @Test
  void openingOmitsTheLinkRatherThanFailingWhenTheTemporalBaseUrlIsUnset() {
    gatewayWithTemporalUi("").open(pullRequest(), "code-review-abc");

    assertThat(parse(sentBodies.get("POST " + CHECK_RUNS)).has("details_url")).isFalse();
  }

  @Test
  void cleanReviewCompletesTheCheckGreen() {
    gateway().complete(pullRequest(), "7", report(Verdict.APPROVE, List.of()));

    JsonNode payload = parse(sentBodies.get("PATCH " + CHECK_RUN_7));
    assertThat(requests).containsExactly("PATCH " + CHECK_RUN_7);
    assertThat(payload.path("status").asText()).isEqualTo("completed");
    assertThat(payload.path("conclusion").asText()).isEqualTo("success");
    assertThat(payload.path("completed_at").asText()).isEqualTo("2026-08-27T12:00:00Z");
  }

  @Test
  void criticalFindingCompletesTheCheckRedEvenUnderAnApproval() {
    gateway().complete(pullRequest(), "7", report(Verdict.APPROVE, List.of(WARNING, CRITICAL)));

    JsonNode payload = parse(sentBodies.get("PATCH " + CHECK_RUN_7));
    assertThat(payload.path("conclusion").asText()).isEqualTo("failure");
    assertThat(payload.path("output").path("title").asText()).contains("1 critical of 2");
  }

  @Test
  void requestingChangesCompletesTheCheckRed() {
    gateway().complete(pullRequest(), "7", report(Verdict.REQUEST_CHANGES, List.of()));

    assertThat(parse(sentBodies.get("PATCH " + CHECK_RUN_7)).path("conclusion").asText())
        .isEqualTo("failure");
  }

  @Test
  void theSummaryCarriesTheReviewsOwnWords() {
    gateway().complete(pullRequest(), "7", report(Verdict.COMMENT, List.of(WARNING)));

    JsonNode output = parse(sentBodies.get("PATCH " + CHECK_RUN_7)).path("output");

    assertThat(output.path("summary").asText()).isEqualTo("Summary.");
  }

  @Test
  void failedReviewCompletesTheCheckRedAndLinksTheRunThatFailed() {
    gateway()
        .fail(
            pullRequest(),
            "7",
            new ReviewFailure(ReviewPhase.REVIEWING, "Claude exited with 1", "code-review-abc"));

    JsonNode output = parse(sentBodies.get("PATCH " + CHECK_RUN_7)).path("output");
    assertThat(parse(sentBodies.get("PATCH " + CHECK_RUN_7)).path("conclusion").asText())
        .isEqualTo("failure");
    assertThat(output.path("title").asText()).contains("REVIEWING");
    assertThat(output.path("summary").asText())
        .contains("Claude exited with 1")
        .contains("have **not** been reviewed")
        .contains("/workflows/code-review-abc");
  }

  /**
   * {@code neutral} is never sent. Whether it satisfies a ruleset's required status check is
   * version-dependent GitHub behaviour, and this check stands between a critical finding and the
   * default branch.
   */
  @Test
  void noConclusionOtherThanSuccessOrFailureIsEverSent() {
    CheckRunGateway gateway = gateway();
    gateway.complete(pullRequest(), "7", report(Verdict.APPROVE, List.of()));
    gateway.complete(pullRequest(), "7", report(Verdict.COMMENT, List.of(CRITICAL)));
    gateway.complete(pullRequest(), "7", report(Verdict.REQUEST_CHANGES, List.of()));
    gateway.fail(pullRequest(), "7", new ReviewFailure(ReviewPhase.PUBLISHING, "boom", "wf"));

    assertThat(parse(sentBodies.get("PATCH " + CHECK_RUN_7)).path("conclusion").asText())
        .isIn("success", "failure");
  }

  @Test
  void rejectedCreateSurfacesAsFailureRatherThanSilentlyMissingId() {
    statuses.put("POST " + CHECK_RUNS, 403);

    assertThatThrownBy(() -> gateway().open(pullRequest(), "wf"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("403");
  }

  @Test
  void responseWithNoIdIsRejectedRatherThanReturningBlankHandle() {
    responses.put("POST " + CHECK_RUNS, "{}");

    assertThatThrownBy(() -> gateway().open(pullRequest(), "wf"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("check run id");
  }

  @Test
  void pathsAreBuiltOnTheRepositoryCollection() {
    assertThat(CheckRunGateway.checkRunsPath("example", "project"))
        .isEqualTo("/repos/example/project/check-runs");
    assertThat(CheckRunGateway.checkRunPath("example", "project", "7"))
        .isEqualTo("/repos/example/project/check-runs/7");
  }

  @Test
  void theTitleNamesTheVerdictAndTheFindingCount() {
    assertThat(CheckRunGateway.completedTitle(report(Verdict.APPROVE, List.of())))
        .isEqualTo("approve — no findings");
    assertThat(CheckRunGateway.completedTitle(report(Verdict.COMMENT, List.of(WARNING))))
        .isEqualTo("comment — 1 finding(s)");
  }

  // --- fixtures ------------------------------------------------------------------------------

  private JsonNode parse(final String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static PullRequestContext pullRequest() {
    return new PullRequestContext(
        "example", "project", 42, "Title", "Body",
        "https://github.com/example/project.git", "base-sha", "head-sha", null);
  }

  private static ReviewReport report(final Verdict verdict, final List<ReviewFinding> findings) {
    return new ReviewReport("Summary.", verdict, findings);
  }

  private CheckRunGateway gateway() {
    return gatewayWithTemporalUi("https://temporal.test");
  }

  private CheckRunGateway gatewayWithTemporalUi(final String temporalUiBaseUrl) {
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "http://localhost:" + server.getAddress().getPort(),
                "test-token",
                "",
                "",
                "",
                java.time.Duration.ofSeconds(30)),
            new CodeReviewProperties.Agent(
                "claude", "sonnet", "medium", 12, java.time.Duration.ofMinutes(15),
                java.nio.file.Path.of("/tmp"), 2097152, 80, "v1"),
            new CodeReviewProperties.Api("token", null), temporalUiBaseUrl);
    return new CheckRunGateway(
        properties,
        new GitHubCredentials(properties, objectMapper),
        objectMapper,
        Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC));
  }
}
