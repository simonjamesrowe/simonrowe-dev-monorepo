package com.simonrowe.factory.flow;

import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
  private final String owner;
  private final String repository;
  private final RestClient gitHub;

  /**
   * Creates a reader scoped to one GitHub repository.
   *
   * @param issues the Linear filing audit trail
   * @param credentials the reviewer's GitHub App credentials, reused rather than duplicated
   * @param owner the GitHub owner both the reviewed repository and {@code agent-setup} live under
   * @param repository the reviewed repository's name
   */
  public ArtifactCountsReader(
      final LinearIssueRepository issues,
      final GitHubCredentials credentials,
      @Value("${factory.github.owner:simonjamesrowe}") final String owner,
      @Value("${factory.github.repository:simonrowe-dev-monorepo}") final String repository) {
    this.issues = issues;
    this.credentials = credentials;
    this.owner = owner;
    this.repository = repository;
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(GITHUB_TIMEOUT).build());
    requestFactory.setReadTimeout(GITHUB_TIMEOUT);
    this.gitHub =
        RestClient.builder().baseUrl("https://api.github.com").requestFactory(requestFactory)
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
      List<?> items = github(credentials.accessToken(installation), path);
      return items == null ? null : new NodeCounts(items.size(), 0, 0);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private List<?> github(final String token, final String path) {
    return gitHub
        .get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
        .retrieve()
        .body(new ParameterizedTypeReference<List<Object>>() {});
  }
}
