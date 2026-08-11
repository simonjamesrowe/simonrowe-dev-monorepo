package com.simonrowe.factory.cvefix.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Finds, opens and comments on the single pull request that carries the scheduled CVE fixes.
 *
 * <p>{@link #findOpen()} is the workflow's step-1 skip check: an existing open pull request means
 * a run is already in flight. Because a false "none open" reading would cause a second pull
 * request to be opened for the same branch, {@link #findOpen()} throws on any non-2xx response or
 * network failure instead of treating it as "none open".
 */
@Component
public class CveFixPrGateway {

  private static final String API_VERSION = "2026-03-10";

  private final CodeReviewProperties codeReviewProperties;
  private final CveFixProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /**
   * Creates a gateway scoped to the configured CVE-fix owner, repository and branch.
   *
   * @param codeReviewProperties supplies the GitHub API base URL and request timeout
   * @param properties supplies the owner, repository, and head branch for the CVE-fix pull
   *     request
   * @param credentials mints the installation access token used to authenticate requests
   * @param objectMapper mapper used to build request payloads and parse responses
   */
  public CveFixPrGateway(
      final CodeReviewProperties codeReviewProperties,
      final CveFixProperties properties,
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
   * Finds the open pull request for the configured head branch, if one exists.
   *
   * @return the open pull request, or {@link Optional#empty()} if GitHub reports none open
   * @throws IllegalStateException if the lookup request fails or returns a non-2xx status; this
   *     is deliberate — misreading an error response as "none open" would let the workflow open a
   *     second CVE pull request while one is already in flight
   */
  public Optional<OpenPullRequest> findOpen() {
    String path =
        "/repos/"
            + properties.owner()
            + "/"
            + properties.repository()
            + "/pulls?head="
            + properties.owner()
            + ":"
            + URLEncoder.encode(properties.branch(), StandardCharsets.UTF_8)
            + "&state=open";
    HttpResponse<String> response = send("GET", path, null, accessToken());
    requireSuccess(response, "GET", path);

    JsonNode results = parseJson(response);
    if (!results.isArray() || results.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(toOpenPullRequest(results.get(0)));
  }

  /**
   * Opens the CVE-fix pull request for the configured branch, or returns the existing one if
   * GitHub reports the branch already has one open.
   *
   * @param title the pull request title
   * @param body the pull request body
   * @return the opened (or already-existing) pull request
   * @throws IllegalStateException if the create request fails with neither a 2xx status nor a 422
   *     that resolves to an existing open pull request
   */
  public OpenPullRequest open(final String title, final String body) {
    String path = "/repos/" + properties.owner() + "/" + properties.repository() + "/pulls";
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("title", title);
    payload.put("head", properties.branch());
    payload.put("base", properties.baseBranch());
    payload.put("body", body);
    // Explicitly false, not omitted: software-factory's own code-review webhook ignores draft
    // pull requests, so a draft CVE pull request would silently never be reviewed. Drafts also
    // save no CI, since pull_request fires for them anyway.
    payload.put("draft", false);

    HttpResponse<String> response = send("POST", path, payload, accessToken());
    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      return toOpenPullRequest(parseJson(response));
    }
    if (response.statusCode() == 422) {
      return findOpen()
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "GitHub API returned 422 for PR creation but found no existing PR for"
                          + " branch "
                          + properties.branch()));
    }
    requireSuccess(response, "POST", path);
    // requireSuccess always throws for the status codes that reach here; this is unreachable.
    throw new IllegalStateException(
        "GitHub API returned unexpected status " + response.statusCode());
  }

  /**
   * Posts a comment on the pull request (issue) with the given number.
   *
   * @param number the pull request number
   * @param body the comment body
   * @throws IllegalStateException if the request fails or returns a non-2xx status
   */
  public void comment(final int number, final String body) {
    String path =
        "/repos/"
            + properties.owner()
            + "/"
            + properties.repository()
            + "/issues/"
            + number
            + "/comments";
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("body", body);

    HttpResponse<String> response = send("POST", path, payload, accessToken());
    requireSuccess(response, "POST", path);
  }

  private String accessToken() {
    Long installationId = credentials.installationId(properties.owner(), properties.repository());
    return credentials.accessToken(installationId);
  }

  private static OpenPullRequest toOpenPullRequest(final JsonNode json) {
    return new OpenPullRequest(json.path("number").asInt(), json.path("html_url").asText());
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

  /**
   * An open CVE pull request: just the number and the URL. Deliberately no head sha — the commit
   * CI is polled for comes from {@code RepositoryWorkspaceFactory.commitAndPush}, which knows the
   * exact sha it pushed, so a sha read back from GitHub here would be a second source of truth
   * with nothing reading it.
   */
  public record OpenPullRequest(int number, String htmlUrl) {
  }
}
