package com.simonrowe.factory.flow;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Live figures for the nodes that are artifacts rather than modules.
 *
 * <p>The Linear figures come from {@code linear_issues} rather than from Linear itself, so the
 * console keeps working when {@code LINEAR_API_KEY} is absent — the collection is the sink's audit
 * trail, and for a count of what the factory has filed it is sufficient. State is re-read from
 * Linear only where a filing decision depends on it, which is not the case here.
 *
 * <p>Every method returns null rather than zero on failure. "Nothing filed" and "could not be
 * read" render as IDLE and UNAVAILABLE respectively, and collapsing them would present a broken
 * source as a quiet one.
 *
 * <p>GitHub reads use plain {@link HttpClient} rather than Spring's {@code RestClient}, matching
 * this module's other outbound HTTP classes — {@code GitHubGateway}, {@code GitHubCredentials},
 * {@code LokiClient} and {@code DependencyTrackClient} — none of which use {@code RestClient}.
 */
@Service
public class ArtifactCountsReader {

  private static final Duration WINDOW = Duration.ofHours(24);

  /** Matches {@code GitHubGateway.API_VERSION}; that constant is private to its own class. */
  private static final String GITHUB_API_VERSION = "2026-03-10";

  /** Bounded so a slow GitHub cannot hang an admin page load. */
  private static final Duration GITHUB_TIMEOUT = Duration.ofSeconds(5);

  private final LinearIssueRepository issues;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final String gitHubApiBaseUrl;
  private final String owner;
  private final String repository;
  private final HttpClient httpClient;

  /**
   * Creates a reader scoped to one GitHub repository.
   *
   * @param issues the Linear filing audit trail
   * @param credentials the reviewer's GitHub App credentials, reused rather than duplicated
   * @param objectMapper mapper used to parse GitHub's JSON responses
   * @param gitHubApiBaseUrl the GitHub API base URL — the same configuration key {@code
   *     GitHubGateway} and {@code GitHubCredentials} read, so an override of {@code
   *     GITHUB_API_URL} applies here too
   * @param owner the GitHub owner both the reviewed repository and {@code agent-setup} live under
   * @param repository the reviewed repository's name
   */
  public ArtifactCountsReader(
      final LinearIssueRepository issues,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper,
      @Value("${factory.codereview.github.api-base-url:https://api.github.com}")
          final String gitHubApiBaseUrl,
      @Value("${factory.github.owner:simonjamesrowe}") final String owner,
      @Value("${factory.github.repository:simonrowe-dev-monorepo}") final String repository) {
    this.issues = issues;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.gitHubApiBaseUrl = gitHubApiBaseUrl;
    this.owner = owner;
    this.repository = repository;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(GITHUB_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /**
   * Counts what the factory has filed into Linear.
   *
   * @return open issues as in-flight and issues closed in the window as settled, or null when the
   *     collection could not be read
   */
  public NodeCounts linearCounts() {
    try {
      List<LinearIssueRecord> all = issues.findAll();
      Instant cutoff = Instant.now().minus(WINDOW);
      int open = 0;
      int settled = 0;
      for (LinearIssueRecord record : all) {
        if (record.lastKnownStateType() == null || record.lastKnownStateType().open()) {
          open++;
        } else if (record.lastSeenAt() != null && record.lastSeenAt().isAfter(cutoff)) {
          settled++;
        }
      }
      return new NodeCounts(open, settled, 0);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  /**
   * Counts open pull requests on the target repository.
   *
   * @return open pull requests as in-flight, or null when GitHub could not be asked
   */
  public NodeCounts pullRequestCounts() {
    return gitHubCount("/repos/" + owner + "/" + repository + "/pulls?state=open&per_page=100");
  }

  /**
   * Counts merges to the default branch inside the window.
   *
   * @return merges as settled, or null when GitHub could not be asked
   */
  public NodeCounts mainCounts() {
    Instant since = Instant.now().minus(WINDOW);
    NodeCounts commits =
        gitHubCount(
            "/repos/" + owner + "/" + repository
                + "/commits?sha=main&since=" + since + "&per_page=100");
    return commits == null ? null : new NodeCounts(0, commits.inFlight(), 0);
  }

  /**
   * Counts open agent-feedback guidance pull requests on {@code agent-setup}.
   *
   * @return open guidance pull requests as in-flight, or null when GitHub could not be asked
   */
  public NodeCounts agentSetupCounts() {
    return gitHubCount("/repos/" + owner + "/agent-setup/pulls?state=open&per_page=100");
  }

  private NodeCounts gitHubCount(final String path) {
    try {
      Long installation = credentials.installationId(owner, repository);
      if (installation == null) {
        return null;
      }
      JsonNode items = github(credentials.accessToken(installation), path);
      return items.isArray() ? new NodeCounts(items.size(), 0, 0) : null;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  /**
   * Fetches a GitHub REST endpoint expected to return a JSON array, the same way {@code
   * GitHubGateway.sendJson} does for the reviewer's own reads.
   *
   * @param token the bearer token to authenticate with
   * @param path the request path, relative to {@link #gitHubApiBaseUrl}
   * @return the parsed response body
   */
  private JsonNode github(final String token, final String path) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(gitHubApiBaseUrl + path))
              .timeout(GITHUB_TIMEOUT)
              .header("Authorization", "Bearer " + token)
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub API returned " + response.statusCode() + " for GET " + path);
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub request interrupted", exception);
    } catch (IOException | JacksonException exception) {
      throw new IllegalStateException("GitHub request failed", exception);
    }
  }
}
