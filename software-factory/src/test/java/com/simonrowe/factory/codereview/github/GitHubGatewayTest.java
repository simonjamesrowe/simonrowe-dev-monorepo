package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewPhase;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubGatewayTest {

  private static final String STATUS_COMMENTS = "/repos/example/project/issues/42/comments";
  private static final String STATUS_COMMENT_55 = "/repos/example/project/issues/comments/55";
  private static final String FINDING_COMMENTS = "/repos/example/project/pulls/42/comments";
  private static final String FINDING_COMMENT_900 = "/repos/example/project/pulls/comments/900";
  private static final String FINDING_COMMENT_901 = "/repos/example/project/pulls/comments/901";
  private static final String REVIEWS = "/repos/example/project/pulls/42/reviews";

  private static final ReviewFinding FINDING =
      new ReviewFinding(Severity.WARNING, "src/App.java", 12, "Bad", "Because.", "Fix it.");
  private static final ReviewFinding DRIFTED =
      new ReviewFinding(Severity.WARNING, "src/Gone.java", 99, "Moved", "Because.", "Fix it.");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final Map<String, String> sentBodies = new ConcurrentHashMap<>();
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();

  /**
   * Request key to a substring that makes that request fail with 422, so a single unanchorable
   * finding can be rejected while its siblings post — which is how GitHub answers, one at a time.
   */
  private final Map<String, String> rejectedBodies = new ConcurrentHashMap<>();

  private HttpServer server;
  private ExecutorService serverExecutor;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    // Without an executor the server dispatches on its own accept thread, which stalls under a
    // loaded suite for long enough to trip the client's request timeout.
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext(
        "/repos/",
        exchange -> {
          String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          requests.add(key);
          String sent =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          sentBodies.merge(key, sent, (previous, latest) -> previous + "\n" + latest);
          String rejectIfPresent = rejectedBodies.get(key);
          int status =
              rejectIfPresent != null && sent.contains(rejectIfPresent)
                  ? 422
                  : statuses.getOrDefault(key, 200);
          byte[] body = responses.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
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

  // --- the sticky status comment -------------------------------------------------------------

  /**
   * The duplication bug: three pushes to pull request 102 left three separate review comments,
   * because publishing never looked for the comment the previous push had already posted.
   */
  @Test
  void openingTheStatusCommentReusesTheOneAnEarlierPushLeftInsteadOfAddingAnother() {
    responses.put(
        "GET " + STATUS_COMMENTS,
        """
        [
          {"id": 12, "body": "Looks good to me"},
          {"id": 55, "body": "<!-- temporal-code-review:example/project#42 -->\\n## Automated"}
        ]
        """);

    String statusCommentId = gateway().openStatusComment(request());

    assertThat(statusCommentId).isEqualTo("55");
    assertThat(requests).contains("PATCH " + STATUS_COMMENT_55);
    assertThat(requests).doesNotContain("POST " + STATUS_COMMENTS);
    assertThat(sentBodies.get("PATCH " + STATUS_COMMENT_55)).contains("in progress");
  }

  @Test
  void openingTheStatusCommentPostsOneWhenThePullRequestHasNoneYet() {
    responses.put("GET " + STATUS_COMMENTS, "[]");
    responses.put("POST " + STATUS_COMMENTS, "{\"id\": 77}");

    String statusCommentId = gateway().openStatusComment(request());

    assertThat(statusCommentId).isEqualTo("77");
    assertThat(requests).contains("POST " + STATUS_COMMENTS);
  }

  @Test
  void markerFromAnotherPullRequestIsNotMistakenForThisOne() {
    responses.put(
        "GET " + STATUS_COMMENTS,
        """
        [{"id": 55, "body": "<!-- temporal-code-review:example/project#41 -->\\n## Automated"}]
        """);
    responses.put("POST " + STATUS_COMMENTS, "{\"id\": 77}");

    String statusCommentId = gateway().openStatusComment(request());

    assertThat(statusCommentId).isEqualTo("77");
    assertThat(requests).doesNotContain("PATCH " + STATUS_COMMENT_55);
  }

  // --- publishing ----------------------------------------------------------------------------

  @Test
  void publishingDeletesTheFindingCommentsEarlierPushesLeftAndKeepsEveryoneElses() {
    responses.put(
        "GET " + FINDING_COMMENTS,
        """
        [
          {"id": 900, "body": "<!-- temporal-code-review-finding -->\\n**warning — Bad**"},
          {"id": 901, "body": "I disagree with this one"}
        ]
        """);

    gateway().publishReview(pullRequest(), report(List.of(FINDING)), "55");

    assertThat(requests).contains("DELETE " + FINDING_COMMENT_900);
    assertThat(requests).doesNotContain("DELETE " + FINDING_COMMENT_901);
  }

  @Test
  void publishingPutsTheSummaryInTheStatusCommentSoTheNextPushReplacesIt() {
    responses.put("GET " + FINDING_COMMENTS, "[]");

    gateway().publishReview(pullRequest(), report(List.of(FINDING)), "55");

    assertThat(requests).contains("PATCH " + STATUS_COMMENT_55);
    assertThat(sentBodies.get("PATCH " + STATUS_COMMENT_55)).contains("Summary.");
    assertThat(sentBodies.get("PATCH " + STATUS_COMMENT_55)).contains("Commit `head-sh`");
  }

  /**
   * Findings go on as individual comments rather than as one submitted review. A submitted review
   * can be neither deleted nor hidden, so three pushes would leave three review entries behind
   * even once their comments were pruned — and GitHub rejects a {@code COMMENT} review with no
   * body, so there is no empty review to post either.
   */
  @Test
  void findingsArePostedAsIndividualCommentsRatherThanOneSubmittedReview() {
    responses.put("GET " + FINDING_COMMENTS, "[]");

    gateway().publishReview(pullRequest(), report(List.of(FINDING)), "55");

    assertThat(requests).doesNotContain("POST " + REVIEWS);
    JsonNode comment = parse(sentBodies.get("POST " + FINDING_COMMENTS));
    assertThat(comment.path("commit_id").asText()).isEqualTo("head-sha");
    assertThat(comment.path("path").asText()).isEqualTo("src/App.java");
    assertThat(comment.path("line").asInt()).isEqualTo(12);
    assertThat(comment.path("side").asText()).isEqualTo("RIGHT");
    assertThat(comment.path("body").asText()).contains("Bad");
  }

  /**
   * The push that fixes everything is exactly the one that must clear the board. Pruning only when
   * there is something new to say would leave the last push's warnings sitting under an approval.
   */
  @Test
  void reviewWithNoFindingsStillDeletesTheOnesTheLastPushLeft() {
    responses.put(
        "GET " + FINDING_COMMENTS,
        "[{\"id\": 900, \"body\": \"<!-- temporal-code-review-finding -->\"}]");

    gateway().publishReview(pullRequest(), report(List.of()), "55");

    assertThat(requests).contains("DELETE " + FINDING_COMMENT_900);
    assertThat(requests).doesNotContain("POST " + FINDING_COMMENTS);
    assertThat(requests).contains("PATCH " + STATUS_COMMENT_55);
  }

  @Test
  void findingThatCannotAnchorToTheDiffFallsBackIntoTheStatusComment() {
    responses.put("GET " + FINDING_COMMENTS, "[]");
    rejectedBodies.put("POST " + FINDING_COMMENTS, "src/Gone.java");

    gateway().publishReview(pullRequest(), report(List.of(FINDING, DRIFTED)), "55");

    String summary = sentBodies.get("PATCH " + STATUS_COMMENT_55);
    assertThat(summary).contains("### Findings");
    assertThat(summary).contains("src/Gone.java:99");
    // The one that did anchor stays inline rather than being repeated in the summary.
    assertThat(summary).doesNotContain("src/App.java:12");
  }

  @Test
  void publishingPostsTheSummaryFreshWhenOpeningTheStatusCommentHadFailed() {
    responses.put("GET " + FINDING_COMMENTS, "[]");

    gateway().publishReview(pullRequest(), report(List.of(FINDING)), null);

    assertThat(requests).contains("POST " + STATUS_COMMENTS);
  }

  @Test
  void findingCommentAlreadyGoneIsNotTreatedAsFailure() {
    responses.put(
        "GET " + FINDING_COMMENTS,
        "[{\"id\": 900, \"body\": \"<!-- temporal-code-review-finding -->\"}]");
    statuses.put("DELETE " + FINDING_COMMENT_900, 404);

    gateway().publishReview(pullRequest(), report(List.of(FINDING)), "55");

    assertThat(requests).contains("POST " + FINDING_COMMENTS);
  }

  // --- failures ------------------------------------------------------------------------------

  @Test
  void failureReplacesTheStatusCommentInPlaceRatherThanAddingToIt() {
    gateway()
        .publishFailure(
            request(),
            "55",
            new ReviewFailure(ReviewPhase.REVIEWING, "Claude exited with 1", "wf-9"));

    assertThat(requests).containsExactly("PATCH " + STATUS_COMMENT_55);
    assertThat(sentBodies.get("PATCH " + STATUS_COMMENT_55)).contains("Claude exited with 1");
  }

  // --- pure helpers --------------------------------------------------------------------------

  /**
   * The marker used to carry the head SHA, so it could never match the comment left by the
   * previous push — which is why nothing was ever found to update.
   */
  @Test
  void theMarkerIdentifiesThePullRequestNotTheCommitSoLaterPushesFindIt() {
    assertThat(GitHubGateway.statusMarker("example", "project", 42))
        .isEqualTo("<!-- temporal-code-review:example/project#42 -->");
  }

  @Test
  void statusCommentsAreFoundOnTheIssueCollectionAndEditedByIdOnTheRepository() {
    assertThat(GitHubGateway.statusCommentsPath("example", "project", 42))
        .isEqualTo("/repos/example/project/issues/42/comments");
    assertThat(GitHubGateway.statusCommentPath("example", "project", "987"))
        .isEqualTo("/repos/example/project/issues/comments/987");
  }

  @Test
  void findingCommentsAreListedOnThePullRequestAndDeletedByIdOnTheRepository() {
    assertThat(GitHubGateway.findingCommentsPath("example", "project", 42))
        .isEqualTo("/repos/example/project/pulls/42/comments");
    assertThat(GitHubGateway.findingCommentPath("example", "project", "900"))
        .isEqualTo("/repos/example/project/pulls/comments/900");
  }

  @Test
  void usesBaseRepositoryCloneUrlBecauseItOwnsThePullRequestRef() throws Exception {
    JsonNode payload =
        objectMapper.readTree(
            """
            {
              "title": "Review me",
              "body": "Description",
              "head": {
                "sha": "head-sha",
                "repo": {"clone_url": "https://github.com/contributor/fork.git"}
              },
              "base": {
                "sha": "base-sha",
                "repo": {"clone_url": "https://github.com/example/project.git"}
              }
            }
            """);

    PullRequestContext result =
        GitHubGateway.toPullRequestContext(
            new ReviewRequest("example", "project", 42, "head-sha", 123L, false), payload);

    assertThat(result.cloneUrl()).isEqualTo("https://github.com/example/project.git");
    assertThat(result.baseSha()).isEqualTo("base-sha");
    assertThat(result.headSha()).isEqualTo("head-sha");
    assertThat(result.installationId()).isEqualTo(123L);
  }

  // --- fixtures ------------------------------------------------------------------------------

  private JsonNode parse(final String json) {
    try {
      return objectMapper.readTree(json);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static ReviewRequest request() {
    return new ReviewRequest("example", "project", 42, "head-sha", null, true);
  }

  private static PullRequestContext pullRequest() {
    return new PullRequestContext(
        "example", "project", 42, "Title", "Body",
        "https://github.com/example/project.git", "base-sha", "head-sha", null);
  }

  private static ReviewReport report(final List<ReviewFinding> findings) {
    return new ReviewReport("Summary.", Verdict.COMMENT, findings);
  }

  private GitHubGateway gateway() {
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
            new CodeReviewProperties.Api("token"), "https://temporal.test");
    return new GitHubGateway(
        properties,
        new GitHubCredentials(properties, objectMapper),
        objectMapper,
        new ReviewMarkdownRenderer());
  }
}
