package com.simonrowe.factory.feedback.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Opens or finds a GitHub pull request for feedback, labels it, and returns its URL.
 *
 * <p>This gateway handles PR creation/discovery and labeling as part of a feedback loop workflow.
 */
@Component
public class FeedbackPrGateway {

  private static final String API_VERSION = "2026-03-10";

  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public FeedbackPrGateway(
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

  /**
   * Opens (or finds, on a re-drive) the PR for a pushed branch and labels it agent-feedback.
   * Returns the PR html_url.
   */
  public String openProposal(
      final String owner,
      final String repository,
      final String branch,
      final String baseBranch,
      final String title,
      final String body,
      final String label,
      final Long installationId) {
    String accessToken = credentials.accessToken(installationId);
    String path = "/repos/" + owner + "/" + repository + "/pulls";

    // Step 1: Try to create the PR
    ObjectNode payload = pullRequestPayload(objectMapper, branch, baseBranch, title, body);
    HttpResponse<String> response = send("POST", path, payload, accessToken);

    int prNumber;
    String htmlUrl;

    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      // PR created successfully
      JsonNode responseBody = parseJson(response);
      prNumber = responseBody.path("number").asInt();
      htmlUrl = responseBody.path("html_url").asText();
    } else if (response.statusCode() == 422) {
      // Step 2: PR already exists, find it
      String encodedBranch = URLEncoder.encode(branch, StandardCharsets.UTF_8);
      String findPath = path + "?head=" + owner + ":" + encodedBranch + "&state=open";
      HttpResponse<String> findResponse = send("GET", findPath, null, accessToken);
      requireSuccess(findResponse, "GET", findPath);

      JsonNode responseArray = parseJson(findResponse);
      if (!responseArray.isArray() || responseArray.isEmpty()) {
        throw new IllegalStateException(
            "GitHub API returned 422 for PR creation but found no existing PR for branch "
                + branch);
      }

      JsonNode existingPr = responseArray.get(0);
      prNumber = existingPr.path("number").asInt();
      htmlUrl = existingPr.path("html_url").asText();
    } else {
      requireSuccess(response, "POST", path);
      // Should not reach here
      throw new IllegalStateException(
          "GitHub API returned unexpected status " + response.statusCode());
    }

    // Step 3: Add label to the PR
    String labelPath = "/repos/" + owner + "/" + repository + "/issues/" + prNumber + "/labels";
    ObjectNode labelPayload = objectMapper.createObjectNode();
    labelPayload.putArray("labels").add(label);
    HttpResponse<String> labelResponse = send("POST", labelPath, labelPayload, accessToken);
    requireSuccess(labelResponse, "POST", labelPath);

    // Step 4: Return html_url
    return htmlUrl;
  }

  /**
   * Creates the payload for a pull request.
   *
   * @param objectMapper Jackson ObjectMapper
   * @param branch the head branch name
   * @param baseBranch the base branch name
   * @param title the PR title
   * @param body the PR body description
   * @return an ObjectNode with keys: head, base, title, body
   */
  static ObjectNode pullRequestPayload(
      final ObjectMapper objectMapper,
      final String branch,
      final String baseBranch,
      final String title,
      final String body) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("head", branch);
    payload.put("base", baseBranch);
    payload.put("title", title);
    payload.put("body", body);
    return payload;
  }

  private JsonNode parseJson(final HttpResponse<String> response) {
    try {
      return response.body().isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(response.body());
    } catch (IOException exception) {
      throw new IllegalStateException("GitHub response was not valid JSON", exception);
    }
  }

  private void requireSuccess(
      final HttpResponse<String> response, final String method, final String path) {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "GitHub API returned " + response.statusCode() + " for " + method + " " + path);
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
}
