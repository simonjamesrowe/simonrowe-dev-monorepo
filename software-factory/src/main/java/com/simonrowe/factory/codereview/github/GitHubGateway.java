package com.simonrowe.factory.codereview.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
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
    String accessToken = credentials.accessToken(pullRequest.installationId());
    if (accessToken.isBlank()) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Publishing a GitHub review requires GitHub credentials",
          "MISSING_GITHUB_CREDENTIALS");
    }
    String marker =
        "<!-- temporal-code-review:"
            + pullRequest.headSha()
            + ":"
            + properties.agent().promptVersion()
            + " -->";
    String path =
        "/repos/"
            + pullRequest.owner()
            + "/"
            + pullRequest.repository()
            + "/pulls/"
            + pullRequest.pullNumber()
            + "/reviews";

    HttpResponse<String> response =
        send("POST", path, reviewPayload(pullRequest, report, marker), accessToken);
    if (response.statusCode() == 422) {
      // At least one finding did not anchor to the diff; fold everything into the body.
      response =
          send("POST", path, fallbackReviewPayload(pullRequest, report, marker), accessToken);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "GitHub API returned " + response.statusCode() + " for POST " + path);
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
