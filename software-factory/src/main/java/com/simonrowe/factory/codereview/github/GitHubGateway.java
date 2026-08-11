package com.simonrowe.factory.codereview.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Minimal GitHub REST adapter.
 *
 * <p>App authentication can replace its token without workflow changes.
 */
@Component
public class GitHubGateway {

  private static final String API_VERSION = "2026-03-10";

  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final ReviewMarkdownRenderer renderer;
  private final HttpClient httpClient;

  public GitHubGateway(
      final CodeReviewProperties properties,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper,
      final ReviewMarkdownRenderer renderer) {
    this.properties = properties;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.renderer = renderer;
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

  public void publishReview(
      final PullRequestContext pullRequest, final ReviewReport report) {
    String marker = marker(pullRequest.headSha());
    String accessToken = requireAccessToken(pullRequest);
    String path = reviewsPath(pullRequest);
    HttpResponse<String> response =
        send("POST", path, reviewPayload(pullRequest, report, marker), accessToken);
    if (response.statusCode() == 422) {
      // At least one finding did not anchor to the diff; fold everything into the body.
      response =
          send("POST", path, fallbackReviewPayload(pullRequest, report, marker), accessToken);
    }
    requireSuccess(response, "POST", path);
  }

  static String ackPath(final ReviewRequest request) {
    return "/repos/"
        + request.owner()
        + "/"
        + request.repository()
        + "/issues/"
        + request.pullNumber()
        + "/comments";
  }

  static String commentPath(final ReviewRequest request, final String commentId) {
    return "/repos/"
        + request.owner()
        + "/"
        + request.repository()
        + "/issues/comments/"
        + commentId;
  }

  /** Edits the ack where there is one; a review whose ack never landed still gets reported. */
  static String failureMethod(final String ackCommentId) {
    return ackCommentId == null ? "POST" : "PATCH";
  }

  /**
   * Announces that a review has started, returning the comment id so it can be resolved later.
   *
   * <p>Posted before anything else, from {@link ReviewRequest} alone, so it lands even when the run
   * dies loading the pull request — the failure that produced no comment at all on 2026-08-11.
   */
  public String publishAck(final ReviewRequest request) {
    String marker = marker(request.expectedHeadSha());
    JsonNode created =
        sendJson(
            "POST",
            ackPath(request),
            objectMapper.createObjectNode().put("body", renderer.renderAck(marker)),
            credentials.commentToken(request.installationId()));
    String id = created.path("id").asText();
    if (id.isBlank()) {
      throw new IllegalStateException("GitHub response omitted the ack comment id");
    }
    return id;
  }

  /** Removes the ack once the review itself is on the pull request. */
  public void resolveAck(final ReviewRequest request, final String ackCommentId) {
    String path = commentPath(request, ackCommentId);
    HttpResponse<String> response =
        send("DELETE", path, null, credentials.commentToken(request.installationId()));
    requireSuccess(response, "DELETE", path);
  }

  /**
   * Reports that no review happened, replacing the ack in place where there is one.
   *
   * <p>Takes {@link ReviewRequest} rather than {@link PullRequestContext} deliberately: the most
   * common failure happens while loading that context, so requiring it is what made the commonest
   * failure unreportable.
   */
  public void publishFailure(
      final ReviewRequest request, final String ackCommentId, final ReviewFailure failure) {
    String marker = marker(request.expectedHeadSha());
    String body = renderer.renderFailure(failure, marker, properties.temporalUiBaseUrl());
    String accessToken = credentials.commentToken(request.installationId());
    ObjectNode payload = objectMapper.createObjectNode().put("body", body);

    String method = failureMethod(ackCommentId);
    String path = ackCommentId == null ? ackPath(request) : commentPath(request, ackCommentId);
    requireSuccess(send(method, path, payload, accessToken), method, path);
  }

  private String marker(final String headSha) {
    return "<!-- temporal-code-review:"
        + headSha
        + ":"
        + properties.agent().promptVersion()
        + " -->";
  }

  private String reviewsPath(final PullRequestContext pullRequest) {
    return "/repos/"
        + pullRequest.owner()
        + "/"
        + pullRequest.repository()
        + "/pulls/"
        + pullRequest.pullNumber()
        + "/reviews";
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

  ObjectNode reviewPayload(
      final PullRequestContext pullRequest, final ReviewReport report, final String marker) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("commit_id", pullRequest.headSha());
    payload.put("event", "COMMENT");
    payload.put("body", renderer.renderReviewBody(report, marker, List.of()));
    ArrayNode comments = payload.putArray("comments");
    for (ReviewFinding finding : report.findings()) {
      ObjectNode comment = comments.addObject();
      comment.put("path", finding.file());
      comment.put("line", finding.line());
      comment.put("side", "RIGHT");
      comment.put("body", renderer.renderFindingComment(finding));
    }
    return payload;
  }

  ObjectNode fallbackReviewPayload(
      final PullRequestContext pullRequest, final ReviewReport report, final String marker) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("commit_id", pullRequest.headSha());
    payload.put("event", "COMMENT");
    payload.put("body", renderer.renderReviewBody(report, marker, report.findings()));
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
    } catch (IOException exception) {
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
