package com.simonrowe.factory.codereview.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.ExistingThread;
import com.simonrowe.factory.codereview.domain.FindingFingerprint;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ThreadAction;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

/**
 * Reads and resolves a pull request's review conversations over GitHub's GraphQL API.
 *
 * <p>GraphQL rather than REST because REST cannot see thread resolution state and offers no way to
 * set it. That limitation is the whole reason the previous implementation deleted every inline
 * comment on every re-review: with no thread identity and no resolved flag, delete-and-repost was
 * the only strategy available. It was also the wrong one — deletion is not resolution, a standing
 * finding read as brand new on every push, GitHub's "N resolved" counter stayed permanently zero,
 * and a thread root was destroyed even when a human had replied to it.
 *
 * <p>Resolution needs only {@code pull_requests: write}, which the installation token already
 * carries — so unlike the {@code Code Review} check run, none of this required a new App grant.
 *
 * <p>Deliberately duplicates the compact {@code HttpClient} plumbing in {@link GitHubGateway} and
 * {@code ConversationGateway} rather than extending either. The three gateways serve different
 * concerns and are kept independently evolvable; that is the same call {@code ConversationGateway}
 * records in its own javadoc.
 */
@Component
public class ReviewThreadGateway {

  private static final String API_VERSION = "2026-03-10";

  /**
   * Bounded at 100 threads and 50 comments per thread, matching {@code ConversationGateway}.
   *
   * <p>A pull request with more than a hundred review conversations is not a case worth paginating
   * for, and the cost of missing one is a duplicate thread rather than a lost finding.
   */
  private static final String THREADS_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            reviewThreads(first: 100) {
              nodes {
                id
                isResolved
                comments(first: 50) {
                  nodes { body author { login __typename } }
                }
              }
            }
          }
        }
      }
      """;

  private static final String REPLY_MUTATION =
      """
      mutation($threadId: ID!, $body: String!) {
        addPullRequestReviewThreadReply(
            input: {pullRequestReviewThreadId: $threadId, body: $body}) {
          comment { id }
        }
      }
      """;

  private static final String RESOLVE_MUTATION =
      """
      mutation($threadId: ID!) {
        resolveReviewThread(input: {threadId: $threadId}) {
          thread { id isResolved }
        }
      }
      """;

  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public ReviewThreadGateway(
      final CodeReviewProperties properties,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper) {
    this.properties = properties;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /** Every review conversation currently on the pull request, with its resolution state. */
  public List<ExistingThread> fetchThreads(final PullRequestContext pullRequest) {
    ObjectNode variables =
        objectMapper
            .createObjectNode()
            .put("owner", pullRequest.owner())
            .put("name", pullRequest.repository())
            .put("number", pullRequest.pullNumber());
    ObjectNode payload =
        objectMapper.createObjectNode().put("query", THREADS_QUERY).set("variables", variables);

    JsonNode root = postJson(payload, credentials.accessToken(pullRequest.installationId()));
    JsonNode node = root.path("data").path("repository").path("pullRequest");
    if (hasErrors(root) || node.isMissingNode() || node.isNull()) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Pull request review threads could not be read", "REVIEW_THREADS_UNAVAILABLE");
    }
    return toExistingThreads(node);
  }

  /**
   * Decides what to do with each existing conversation and each new finding.
   *
   * <p>Pure, static and HTTP-free so the decision table itself is the unit under test, following
   * {@code GitHubGateway.toPullRequestContext} and {@code ConversationGateway.toConversation}.
   *
   * <table>
   *   <caption>The reconcile contract</caption>
   *   <tr><th>Thread</th><th>Fingerprint in new report</th><th>Action</th></tr>
   *   <tr><td>open</td><td>yes</td><td>leave untouched</td></tr>
   *   <tr><td>resolved</td><td>yes</td><td>post a fresh thread — it regressed</td></tr>
   *   <tr><td>open</td><td>no</td><td>reply, then resolve</td></tr>
   *   <tr><td>resolved</td><td>no</td><td>leave — already resolved</td></tr>
   *   <tr><td>none</td><td>yes</td><td>post a new thread</td></tr>
   *   <tr><td>not this reviewer's</td><td>n/a</td><td>leave — never touch it</td></tr>
   * </table>
   *
   * <p>There is no row that deletes anything, and {@link ThreadAction} has no case for it.
   */
  static List<ThreadAction> reconcile(
      final List<ExistingThread> threads, final List<ReviewFinding> findings) {
    Set<String> reported = new LinkedHashSet<>();
    for (ReviewFinding finding : findings) {
      reported.add(FindingFingerprint.of(finding));
    }

    List<ThreadAction> actions = new ArrayList<>();
    // Fingerprints that already have an open thread, so the finding must not be posted again.
    Set<String> covered = new HashSet<>();

    for (ExistingThread thread : threads) {
      if (!thread.reviewerOwned()) {
        // A human's question or a third-party analyser's comment. Required conversation
        // resolution covers it too, but resolving it is a judgement only a person can make.
        actions.add(new ThreadAction.Leave(thread.nodeId()));
        continue;
      }
      boolean stillReported =
          thread.fingerprint() != null && reported.contains(thread.fingerprint());
      if (stillReported && !thread.resolved()) {
        covered.add(thread.fingerprint());
        actions.add(new ThreadAction.Leave(thread.nodeId()));
      } else if (thread.resolved()) {
        // Already resolved. If the finding is back it gets a fresh thread below, because
        // reopening a resolved conversation hides the fact that it regressed.
        actions.add(new ThreadAction.Leave(thread.nodeId()));
      } else {
        // Open, and this reviewer no longer reports it — including every legacy bare-marker
        // thread, which carries no fingerprint and so can never match a new report.
        actions.add(new ThreadAction.ReplyAndResolve(thread.nodeId()));
      }
    }

    for (ReviewFinding finding : findings) {
      if (!covered.contains(FindingFingerprint.of(finding))) {
        actions.add(new ThreadAction.PostNew(finding));
      }
    }
    return actions;
  }

  /**
   * Replies to a conversation and then resolves it.
   *
   * <p>Reply first, always: a resolution landing without its explanation is indistinguishable from
   * someone quietly closing an inconvenient finding.
   *
   * <p>The wording is "no longer reported", never "fixed". The fingerprint is only as stable as the
   * model's phrasing of the title, so a re-worded title reads as one resolved and one new — and
   * claiming a fix in that case would be false. "No longer reported" is true either way.
   */
  public void replyAndResolve(
      final PullRequestContext pullRequest, final String threadId, final String headSha) {
    String token = credentials.accessToken(pullRequest.installationId());
    mutate(REPLY_MUTATION, replyVariables(threadId, headSha), token);
    mutate(RESOLVE_MUTATION, objectMapper.createObjectNode().put("threadId", threadId), token);
  }

  private ObjectNode replyVariables(final String threadId, final String headSha) {
    return objectMapper
        .createObjectNode()
        .put("threadId", threadId)
        .put("body", "No longer reported as of `" + shortSha(headSha) + "`.");
  }

  private static String shortSha(final String headSha) {
    if (headSha == null || headSha.isBlank()) {
      return "the current commit";
    }
    return headSha.length() > 7 ? headSha.substring(0, 7) : headSha;
  }

  /** Maps the GraphQL response, mirroring {@code ConversationGateway.toConversation}. */
  static List<ExistingThread> toExistingThreads(final JsonNode pullRequest) {
    List<ExistingThread> threads = new ArrayList<>();
    for (JsonNode node : pullRequest.path("reviewThreads").path("nodes")) {
      JsonNode comments = node.path("comments").path("nodes");
      String rootBody = comments.isArray() && !comments.isEmpty()
          ? comments.get(0).path("body").asText("")
          : "";

      boolean nonBotReply = false;
      for (int index = 1; index < comments.size(); index++) {
        if (!"Bot".equals(comments.get(index).path("author").path("__typename").asText(""))) {
          nonBotReply = true;
          break;
        }
      }

      threads.add(
          new ExistingThread(
              node.path("id").asText(""),
              fingerprintOf(rootBody),
              rootBody.contains(ReviewMarkdownRenderer.LEGACY_FINDING_MARKER),
              node.path("isResolved").asBoolean(false),
              nonBotReply));
    }
    return threads;
  }

  /** The finding identity embedded in a comment body, or null when there is none. */
  static String fingerprintOf(final String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    Matcher matcher = ReviewMarkdownRenderer.FINDING_MARKER_PATTERN.matcher(body);
    return matcher.find() ? matcher.group(1) : null;
  }

  private void mutate(final String query, final ObjectNode variables, final String token) {
    ObjectNode payload =
        objectMapper.createObjectNode().put("query", query).set("variables", variables);
    JsonNode root = postJson(payload, token);
    if (hasErrors(root)) {
      throw new IllegalStateException(
          "GitHub GraphQL mutation was rejected: " + root.path("errors"));
    }
  }

  private static boolean hasErrors(final JsonNode root) {
    JsonNode errors = root.path("errors");
    return errors.isArray() && !errors.isEmpty();
  }

  private JsonNode postJson(final ObjectNode payload, final String accessToken) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.github().apiBaseUrl() + "/graphql"))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer")
              .header("Content-Type", "application/json");
      if (accessToken != null && !accessToken.isBlank()) {
        request.header("Authorization", "Bearer " + accessToken);
      }
      request.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

      HttpResponse<String> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub GraphQL API returned " + response.statusCode() + " for POST /graphql");
      }
      return response.body().isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub GraphQL request interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("GitHub GraphQL request failed", exception);
    }
  }
}
