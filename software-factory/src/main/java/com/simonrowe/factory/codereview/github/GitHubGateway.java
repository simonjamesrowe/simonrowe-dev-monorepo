package com.simonrowe.factory.codereview.github;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.domain.ThreadAction;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Minimal GitHub REST adapter.
 *
 * <p>Every review of a pull request writes through <em>one</em> comment, found by {@link
 * #statusMarker}. Reviews used to be posted afresh on every push, keyed by head SHA, so a pull
 * request accumulated one summary per push and one copy of each finding per push — pull request 102
 * collected three.
 *
 * <p>Inline findings are <em>reconciled</em>, never deleted. That work belongs to {@link
 * ReviewThreadGateway}, which reaches GitHub over GraphQL because REST can neither read nor set a
 * thread's resolution state.
 *
 * <p>App authentication can replace its token without workflow changes.
 */
@Component
public class GitHubGateway {

  private static final String API_VERSION = "2026-03-10";
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 10;

  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final ReviewMarkdownRenderer renderer;
  private final ReviewThreadGateway reviewThreadGateway;
  private final HttpClient httpClient;

  public GitHubGateway(
      final CodeReviewProperties properties,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper,
      final ReviewMarkdownRenderer renderer,
      final ReviewThreadGateway reviewThreadGateway) {
    this.properties = properties;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.renderer = renderer;
    this.reviewThreadGateway = reviewThreadGateway;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public PullRequestContext loadPullRequest(final ReviewRequest request) {
    String path =
        "/repos/"
            + request.owner()
            + "/"
            + request.repository()
            + "/pulls/"
            + request.pullNumber();
    JsonNode pullRequest =
        sendJson("GET", path, null, credentials.accessToken(request.installationId()));
    return toPullRequestContext(request, pullRequest);
  }

  static PullRequestContext toPullRequestContext(
      final ReviewRequest request, final JsonNode pullRequest) {
    String headSha = requiredText(pullRequest.path("head"), "sha");

    if (request.expectedHeadSha() != null
        && !request.expectedHeadSha().isBlank()
        && !request.expectedHeadSha().equals(headSha)) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Pull request head changed before review started", "STALE_PULL_REQUEST");
    }

    return new PullRequestContext(
        request.owner(),
        request.repository(),
        request.pullNumber(),
        requiredText(pullRequest, "title"),
        pullRequest.path("body").asText(""),
        requiredText(pullRequest.path("base").path("repo"), "clone_url"),
        requiredText(pullRequest.path("base"), "sha"),
        headSha,
        request.installationId());
  }

  /**
   * Opens — or reclaims — the one comment this reviewer keeps on the pull request, returning its id
   * so the outcome can be written into the same place.
   *
   * <p>Posted before anything else, from {@link ReviewRequest} alone, so it lands even when the run
   * dies loading the pull request — the failure that produced no comment at all on 2026-08-11.
   */
  public String openStatusComment(final ReviewRequest request) {
    String token = credentials.commentToken(request.installationId());
    String marker = statusMarker(request.owner(), request.repository(), request.pullNumber());
    ObjectNode payload =
        objectMapper
            .createObjectNode()
            .put("body", renderer.renderAck(marker, request.expectedHeadSha()));

    String existing =
        findCommentId(
            fetchAllPages(
                statusCommentsPath(request.owner(), request.repository(), request.pullNumber()),
                token),
            marker);
    if (existing != null) {
      String path = statusCommentPath(request.owner(), request.repository(), existing);
      requireSuccess(send("PATCH", path, payload, token), "PATCH", path);
      return existing;
    }

    String path = statusCommentsPath(request.owner(), request.repository(), request.pullNumber());
    String id = sendJson("POST", path, payload, token).path("id").asText();
    if (id.isBlank()) {
      throw new IllegalStateException("GitHub response omitted the status comment id");
    }
    return id;
  }

  /**
   * Reconciles the new report against the conversations already on the pull request, then writes
   * the summary into the status comment.
   *
   * <p>Findings that are still reported keep the thread they already have — same posting time, same
   * position, same replies. Findings that have gone are replied to and resolved. Findings with no
   * thread get one. <b>Nothing is deleted.</b>
   *
   * <p>This replaced a delete-everything-and-repost strategy, which was the only option available
   * while findings had no identity, and was wrong on four counts: deletion is not resolution, a
   * standing finding read as brand new on every push, GitHub's "N resolved" counter stayed
   * permanently zero, and deleting a thread root took a human's reply with it.
   *
   * <p>Findings are still posted as individual comments rather than gathered into a submitted
   * review. A submitted review can be neither deleted nor hidden, so one per push would accumulate
   * on the pull request even after its comments were pruned — and GitHub rejects a {@code COMMENT}
   * review with an empty body, so there is no bodiless review to post instead.
   */
  public void publishReview(
      final PullRequestContext pullRequest,
      final ReviewReport report,
      final String statusCommentId) {
    List<ReviewFinding> unanchored = new ArrayList<>();
    String accessToken = requireAccessToken(pullRequest);

    List<ThreadAction> actions =
        ReviewThreadGateway.reconcile(
            reviewThreadGateway.fetchThreads(pullRequest), report.findings());

    String path =
        findingCommentsPath(
            pullRequest.owner(), pullRequest.repository(), pullRequest.pullNumber());
    for (ThreadAction action : actions) {
      switch (action) {
        case ThreadAction.Leave ignored -> {
          // Doing nothing is the entire improvement over the previous behaviour.
        }
        case ThreadAction.ReplyAndResolve resolve ->
            reviewThreadGateway.replyAndResolve(
                pullRequest, resolve.nodeId(), pullRequest.headSha());
        case ThreadAction.PostNew post -> {
          HttpResponse<String> response =
              send("POST", path, findingCommentPayload(pullRequest, post.finding()), accessToken);
          if (response.statusCode() == 422) {
            // This one did not anchor to the current diff; the summary carries it instead.
            unanchored.add(post.finding());
          } else {
            requireSuccess(response, "POST", path);
          }
        }
      }
    }

    publishSummary(pullRequest, report, statusCommentId, unanchored);
  }

  /**
   * Reports that no review happened, replacing the status comment in place where there is one.
   *
   * <p>Takes {@link ReviewRequest} rather than {@link PullRequestContext} deliberately: the most
   * common failure happens while loading that context, so requiring it is what made the commonest
   * failure unreportable.
   */
  public void publishFailure(
      final ReviewRequest request, final String statusCommentId, final ReviewFailure failure) {
    String marker = statusMarker(request.owner(), request.repository(), request.pullNumber());
    String body =
        renderer.renderFailure(
            failure, marker, request.expectedHeadSha(), properties.temporalUiBaseUrl());
    writeStatusComment(
        request.owner(), request.repository(), request.pullNumber(), statusCommentId, body,
        credentials.commentToken(request.installationId()));
  }

  private void publishSummary(
      final PullRequestContext pullRequest,
      final ReviewReport report,
      final String statusCommentId,
      final List<ReviewFinding> unanchored) {
    String marker =
        statusMarker(
            pullRequest.owner(), pullRequest.repository(), pullRequest.pullNumber());
    String body =
        renderer.renderSummary(report, marker, pullRequest.headSha(), unanchored);
    writeStatusComment(
        pullRequest.owner(), pullRequest.repository(), pullRequest.pullNumber(), statusCommentId,
        body, credentials.commentToken(pullRequest.installationId()));
  }

  /** Edits the status comment where there is one; a run whose comment never opened posts one. */
  private void writeStatusComment(
      final String owner,
      final String repository,
      final int pullNumber,
      final String statusCommentId,
      final String body,
      final String token) {
    ObjectNode payload = objectMapper.createObjectNode().put("body", body);
    String method = statusCommentId == null ? "POST" : "PATCH";
    String path =
        statusCommentId == null
            ? statusCommentsPath(owner, repository, pullNumber)
            : statusCommentPath(owner, repository, statusCommentId);
    requireSuccess(send(method, path, payload, token), method, path);
  }

  /**
   * Identifies this reviewer's status comment on a pull request.
   *
   * <p>Scoped to the pull request, not the commit. The marker used to carry the head SHA, which
   * meant a push could never match the comment its predecessor had left, so nothing was ever found
   * to update and every push added one.
   */
  static String statusMarker(final String owner, final String repository, final int pullNumber) {
    return "<!-- temporal-code-review:" + owner + "/" + repository + "#" + pullNumber + " -->";
  }

  static String statusCommentsPath(
      final String owner, final String repository, final int pullNumber) {
    return "/repos/" + owner + "/" + repository + "/issues/" + pullNumber + "/comments";
  }

  static String statusCommentPath(
      final String owner, final String repository, final String commentId) {
    return "/repos/" + owner + "/" + repository + "/issues/comments/" + commentId;
  }

  static String findingCommentsPath(
      final String owner, final String repository, final int pullNumber) {
    return "/repos/" + owner + "/" + repository + "/pulls/" + pullNumber + "/comments";
  }

  /** The id of the first comment carrying {@code marker}, or null when none does. */
  static String findCommentId(final JsonNode comments, final String marker) {
    for (JsonNode comment : comments) {
      if (comment.path("body").asText("").contains(marker)) {
        return comment.path("id").asText();
      }
    }
    return null;
  }

  /**
   * Walks a paged collection into one array.
   *
   * <p>Bounded at {@link #MAX_PAGES}: a pull request with more than a thousand comments is not
   * worth an unbounded loop, and the cost of missing one is a duplicate rather than a failure.
   */
  private JsonNode fetchAllPages(final String path, final String token) {
    ArrayNode all = objectMapper.createArrayNode();
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode batch =
          sendJson("GET", path + "?per_page=" + PAGE_SIZE + "&page=" + page, null, token);
      if (!batch.isArray()) {
        break;
      }
      all.addAll((ArrayNode) batch);
      if (batch.size() < PAGE_SIZE) {
        break;
      }
    }
    return all;
  }

  private String requireAccessToken(final PullRequestContext pullRequest) {
    String accessToken = credentials.accessToken(pullRequest.installationId());
    if (accessToken.isBlank()) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Publishing a GitHub review requires GitHub credentials",
          "MISSING_GITHUB_CREDENTIALS");
    }
    return accessToken;
  }

  private void requireSuccess(
      final HttpResponse<String> response, final String method, final String path) {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "GitHub API returned " + response.statusCode() + " for " + method + " " + path);
    }
  }

  ObjectNode findingCommentPayload(
      final PullRequestContext pullRequest, final ReviewFinding finding) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("commit_id", pullRequest.headSha());
    payload.put("path", finding.file());
    payload.put("line", finding.line());
    payload.put("side", "RIGHT");
    payload.put("body", renderer.renderFindingComment(finding));
    return payload;
  }

  private JsonNode sendJson(
      final String method, final String path, final JsonNode payload, final String accessToken) {
    HttpResponse<String> response = send(method, path, payload, accessToken);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "GitHub API returned " + response.statusCode() + " for " + method + " " + path);
    }
    try {
      return response.body().isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(response.body());
    } catch (JacksonException exception) {
      throw new IllegalStateException("GitHub response was not JSON", exception);
    }
  }

  private HttpResponse<String> send(
      final String method, final String path, final JsonNode payload, final String accessToken) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.github().apiBaseUrl() + path))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer");
      if (!accessToken.isBlank()) {
        request.header("Authorization", "Bearer " + accessToken);
      }
      if (payload == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request
            .header("Content-Type", "application/json")
            .method(
                method,
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
      }

      return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub request interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("GitHub request failed", exception);
    }
  }

  private static String requiredText(final JsonNode node, final String field) {
    String value = node.path(field).asText();
    if (value.isBlank()) {
      throw new IllegalStateException("GitHub response omitted " + field);
    }
    return value;
  }

}
