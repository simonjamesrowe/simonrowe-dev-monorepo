package com.simonrowe.factory.deploy.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.deploy.config.DeployProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Posts a deploy report: a comment on the deployed commit, and a tracked issue.
 *
 * <p>Two places on purpose. The commit comment is where someone looking at the merge will find
 * it; the issue is what does not get lost. Neither on its own is enough — a comment on a commit
 * nobody revisits is invisible, and an issue with no link from the change that caused it is hard
 * to place.
 *
 * <p>Modelled on {@code CveFixPrGateway}: same client, same API version header, same run-time
 * installation-token resolution. Deliberately a separate class rather than an extension of it —
 * that gateway is about one long-lived pull request, and this one about an append-only stream of
 * reports, so the only thing they would share is HTTP plumbing.
 *
 * <p>Reporting failures are not fatal to a deploy: the caller records what it managed to post.
 * A deploy that rolled back successfully but could not comment about it has still done the
 * important part.
 */
@Component
public class DeployReportGateway {

  private static final String API_VERSION = "2026-03-10";

  private final CodeReviewProperties codeReviewProperties;
  private final DeployProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /**
   * Creates a gateway scoped to the configured deploy owner and repository.
   *
   * @param codeReviewProperties supplies the GitHub API base URL and request timeout
   * @param properties supplies the owner and repository
   * @param credentials mints the installation access token used to authenticate requests
   * @param objectMapper mapper used to build request payloads and parse responses
   */
  public DeployReportGateway(
      final CodeReviewProperties codeReviewProperties,
      final DeployProperties properties,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper) {
    this.codeReviewProperties = codeReviewProperties;
    this.properties = properties;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(codeReviewProperties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /**
   * Opens an issue.
   *
   * @param title the issue title
   * @param body the issue body
   * @param labels labels to apply; ignored by GitHub if they do not exist on the repository
   * @param installationId the installation to authenticate as, or null to resolve it at run time
   * @return the issue's html_url
   */
  public String openIssue(
      final String title,
      final String body,
      final List<String> labels,
      final Long installationId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("title", title);
    payload.put("body", body);
    if (labels != null && !labels.isEmpty()) {
      labels.forEach(payload.putArray("labels")::add);
    }

    String path = "/repos/" + properties.owner() + "/" + properties.repository() + "/issues";
    HttpResponse<String> response = send("POST", path, payload, accessToken(installationId));
    requireSuccess(response, "POST", path);
    return parseJson(response).path("html_url").asText();
  }

  /**
   * Comments on a commit.
   *
   * @param sha the commit to comment on
   * @param body the comment body
   * @param installationId the installation to authenticate as, or null to resolve it at run time
   * @return the comment's html_url
   */
  public String commentOnCommit(final String sha, final String body, final Long installationId) {
    String path =
        "/repos/"
            + properties.owner()
            + "/"
            + properties.repository()
            + "/commits/"
            + sha
            + "/comments";
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("body", body);

    HttpResponse<String> response = send("POST", path, payload, accessToken(installationId));
    requireSuccess(response, "POST", path);
    return parseJson(response).path("html_url").asText();
  }

  /**
   * Resolves the installation token.
   *
   * <p>When {@code installationId} is null it is looked up at run time, which is what the CVE-fix
   * flow does and for the same reason: a configured-but-empty installation id would make
   * {@code accessToken(null)} fall back to the static {@code GITHUB_TOKEN}, which this service
   * does not set — giving an anonymous request and a 403 that looks like a permissions problem.
   */
  private String accessToken(final Long installationId) {
    Long resolved =
        installationId != null
            ? installationId
            : credentials.installationId(properties.owner(), properties.repository());
    return credentials.accessToken(resolved);
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
              .uri(URI.create(codeReviewProperties.github().apiBaseUrl() + path))
              .timeout(codeReviewProperties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "simonrowe-deployer");
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
