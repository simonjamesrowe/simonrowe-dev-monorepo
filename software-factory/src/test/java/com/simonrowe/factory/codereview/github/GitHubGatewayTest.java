package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.FindingFingerprint;
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
import java.util.concurrent.atomic.AtomicReference;
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

  /** The review threads GitHub reports, as the JSON array the GraphQL query returns. */
  private final AtomicReference<String> threadNodes = new AtomicReference<>("[]");

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
    // Review threads live on GraphQL, because REST can neither read nor set thread resolution
    // state. The one query and the two mutations are keyed by name so a test can assert which of
    // them ran without matching on the whole query text.
    server.createContext(
        "/graphql",
        exchange -> {
          String sent =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String operation = graphQlOperation(sent);
          requests.add("GRAPHQL " + operation);
          sentBodies.merge("GRAPHQL " + operation, sent, (a, b) -> a + "\n" + b);
          byte[] body =
              ("reviewThreads".equals(operation)
                      ? "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":"
                          + "{\"nodes\":" + threadNodes.get() + "}}}}}"
                      : "{\"data\":{}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  private static String graphQlOperation(final String body) {
    if (body.contains("addPullRequestReviewThreadReply")) {
      return "addPullRequestReviewThreadReply";
    }
    if (body.contains("resolveReviewThread")) {
      return "resolveReviewThread";
    }
    return "reviewThreads";
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

  /**
   * The behaviour this feature exists to change. A finding that is still reported keeps the thread
   * it already has — its posting time, its position, and any reply on it. Previously it was
   * deleted and reposted on every push, so it read as brand new each time and a human's reply went
   * with the thread root.
   */
  @Test
  void findingStillReportedKeepsItsExistingThreadUntouched() {
    threadNodes.set(openThread("T1", FINDING));

    gateway().publishReview(pullRequest(), report(List.of(FINDING)), "55");

    assertThat(requests).doesNotContain("POST " + FINDING_COMMENTS);
    assertThat(requests).doesNotContain("GRAPHQL resolveReviewThread");
  }

  /** A thread this reviewer did not open is never resolved: that judgement belongs to a person. */
  @Test
  void threadOpenedByHumanIsLeftAlone() {
    threadNodes.set(
        """
        [{"id": "H1", "isResolved": false, "comments": {"nodes": [
          {"body": "I disagree with this one", "author": {"login": "s", "__typename": "User"}}
        ]}}]
        """);

    gateway().publishReview(pullRequest(), report(List.of()), "55");

    assertThat(requests).doesNotContain("GRAPHQL resolveReviewThread");
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
   * The push that fixes everything is exactly the one that must clear the board — but by resolving
   * the conversations, not by destroying them. The reply is what makes the resolution legible
   * later, and it says "no longer reported" rather than "fixed" because a re-worded title produces
   * the same state as a genuine fix.
   */
  @Test
  void reviewWithNoFindingsRepliesToAndResolvesTheThreadsTheLastPushLeft() {
    threadNodes.set(openThread("T1", FINDING));

    gateway().publishReview(pullRequest(), report(List.of()), "55");

    assertThat(requests).contains("GRAPHQL addPullRequestReviewThreadReply");
    assertThat(requests).contains("GRAPHQL resolveReviewThread");
    assertThat(sentBodies.get("GRAPHQL addPullRequestReviewThreadReply"))
        .contains("No longer reported as of")
        .doesNotContain("Fixed");
    assertThat(requests).doesNotContain("POST " + FINDING_COMMENTS);
    assertThat(requests).contains("PATCH " + STATUS_COMMENT_55);
  }

  /** Reply first, always: a resolution landing without its explanation is indistinguishable from
   * someone quietly closing an inconvenient finding. */
  @Test
  void theReplyIsPostedBeforeTheThreadIsResolved() {
    threadNodes.set(openThread("T1", FINDING));

    gateway().publishReview(pullRequest(), report(List.of()), "55");

    assertThat(requests.indexOf("GRAPHQL addPullRequestReviewThreadReply"))
        .isLessThan(requests.indexOf("GRAPHQL resolveReviewThread"));
  }

  /** A resolved thread whose finding is back gets a fresh thread — reopening the old one would
   * hide that it regressed. */
  @Test
  void findingThatRegressedAfterBeingResolvedGetsFreshThread() {
    threadNodes.set(resolvedThread("T1", FINDING));

    gateway().publishReview(pullRequest(), report(List.of(FINDING)), "55");

    assertThat(requests).contains("POST " + FINDING_COMMENTS);
    assertThat(requests).doesNotContain("GRAPHQL resolveReviewThread");
  }

  /**
   * The guarantee the whole feature rests on. Deletion was never resolution: it left GitHub's "N
   * resolved" counter permanently zero and took human replies down with the thread root.
   */
  @Test
  void publishingNeverDeletesAnything() {
    threadNodes.set(openThread("T1", FINDING));

    gateway().publishReview(pullRequest(), report(List.of(DRIFTED)), "55");

    assertThat(requests).noneMatch(request -> request.startsWith("DELETE "));
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

  /**
   * A thread from before findings carried identity matches no fingerprint, so the first review
   * after deploy replies to it and resolves it. That is the right outcome for a pre-change
   * artefact, and it destroys nothing.
   */
  @Test
  void legacyThreadFromBeforeFindingsHadIdentityIsResolvedRatherThanDeleted() {
    threadNodes.set(
        """
        [{"id": "L1", "isResolved": false, "comments": {"nodes": [
          {"body": "<!-- temporal-code-review-finding -->\\n**warning — Bad**",
           "author": {"login": "bot", "__typename": "Bot"}}
        ]}}]
        """);

    gateway().publishReview(pullRequest(), report(List.of(FINDING)), "55");

    assertThat(requests).contains("GRAPHQL resolveReviewThread");
    assertThat(requests).noneMatch(request -> request.startsWith("DELETE "));
    // The finding is still reported, so it gets a thread that does carry identity.
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
  void findingCommentsArePostedOnThePullRequestCollection() {
    assertThat(GitHubGateway.findingCommentsPath("example", "project", 42))
        .isEqualTo("/repos/example/project/pulls/42/comments");
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

  /** One open thread this reviewer opened for {@code finding}, as GraphQL reports it. */
  private static String openThread(final String nodeId, final ReviewFinding finding) {
    return threadJson(nodeId, finding, false);
  }

  private static String resolvedThread(final String nodeId, final ReviewFinding finding) {
    return threadJson(nodeId, finding, true);
  }

  private static String threadJson(
      final String nodeId, final ReviewFinding finding, final boolean resolved) {
    return """
        [{"id": "%s", "isResolved": %s, "comments": {"nodes": [
          {"body": "%s", "author": {"login": "bot", "__typename": "Bot"}}
        ]}}]
        """
        .formatted(
            nodeId,
            resolved,
            ReviewMarkdownRenderer.findingMarker(FindingFingerprint.of(finding)));
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
    GitHubCredentials credentials = new GitHubCredentials(properties, objectMapper);
    return new GitHubGateway(
        properties,
        credentials,
        objectMapper,
        new ReviewMarkdownRenderer(),
        new ReviewThreadGateway(properties, credentials, objectMapper));
  }
}
