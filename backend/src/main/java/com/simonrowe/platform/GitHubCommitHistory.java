package com.simonrowe.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads {@code main}'s recent history from the GitHub REST API.
 *
 * <p><b>Why the API rather than the image.</b> The changelog used to be {@code git log -n 50}
 * baked into the backend image at build time. Publish now rebuilds only the images whose inputs
 * changed, so a frontend-only merge no longer produces a backend image, and a baked changelog
 * would have stopped at the last backend change. The repository is public, so this needs no
 * credential.
 *
 * <p><b>The anonymous rate limit is 60 requests an hour per address</b>, which is what shapes
 * this class. The listing is sent with {@code If-None-Match}: GitHub does not count a
 * {@code 304 Not Modified} against the limit, so polling an unchanged branch is free. Files cost
 * one request per commit and are fetched only for commits not yet stored, capped per poll by
 * {@link ReleaseRecorder}.
 */
@Component
public class GitHubCommitHistory implements CommitHistory {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
  private static final String API_VERSION = "2022-11-28";
  private static final String USER_AGENT = "simonrowe-dev-backend";

  private final RestClient client;
  private final String repository;
  private final String branch;
  private final int depth;
  private final AtomicReference<CachedListing> listing = new AtomicReference<>(null);

  /**
   * Creates the client.
   *
   * @param baseUrl the GitHub API base URL
   * @param repository {@code owner/name} of the repository whose history is the changelog
   * @param branch the branch to read
   * @param depth how many commits to list
   * @param timeout connect and read timeout
   */
  public GitHubCommitHistory(
      @Value("${platform.releases.history.api-base-url:https://api.github.com}")
      final String baseUrl,
      @Value("${platform.releases.history.repository:simonjamesrowe/simonrowe-dev-monorepo}")
      final String repository,
      @Value("${platform.releases.history.branch:main}") final String branch,
      @Value("${platform.releases.history.depth:50}") final int depth,
      @Value("${platform.releases.history.timeout:10s}") final Duration timeout) {
    this.client = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.ACCEPT, ACCEPT)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .defaultHeader(API_VERSION_HEADER, API_VERSION)
        .requestFactory(new RestTemplateBuilder()
            .connectTimeout(timeout)
            .readTimeout(timeout)
            .buildRequestFactory())
        .build();
    this.repository = repository;
    this.branch = branch;
    this.depth = depth;
  }

  @Override
  public List<MainCommit> recent() {
    CachedListing cached = listing.get();
    CachedListing fetched = client.get()
        .uri("/repos/{repository}/commits?sha={branch}&per_page={depth}",
            repository, branch, depth)
        .headers(headers -> {
          if (cached != null && cached.etag() != null) {
            headers.setIfNoneMatch(cached.etag());
          }
        })
        .exchange((request, response) -> {
          if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED) && cached != null) {
            return cached;
          }
          if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RestClientException(
                "GitHub commit listing returned " + response.getStatusCode().value());
          }
          ListedCommit[] body = response.bodyTo(ListedCommit[].class);
          return new CachedListing(response.getHeaders().getETag(), toCommits(body));
        });
    listing.set(fetched);
    return fetched.commits();
  }

  @Override
  public List<String> filesChanged(final String sha) {
    CommitDetail detail = client.get()
        .uri("/repos/{repository}/commits/{sha}", repository, sha)
        .retrieve()
        .body(CommitDetail.class);
    // Kept even though RestClient documents a non-null body for a 200: a future client version,
    // or a proxy answering with an empty 200, must not NPE the poll.
    if (detail == null || detail.files() == null) {
      return List.of();
    }
    return detail.files().stream()
        .map(ChangedFile::filename)
        .filter(name -> name != null && !name.isBlank())
        .toList();
  }

  private static List<MainCommit> toCommits(final ListedCommit[] body) {
    if (body == null) {
      return List.of();
    }
    List<MainCommit> commits = new ArrayList<>();
    for (ListedCommit listed : body) {
      if (listed == null || listed.sha() == null || listed.commit() == null) {
        continue;
      }
      Instant committed = listed.commit().committer() == null
          ? null : listed.commit().committer().date();
      if (committed == null) {
        continue;
      }
      String message = listed.commit().message() == null ? "" : listed.commit().message();
      int newline = message.indexOf('\n');
      String subject = (newline < 0 ? message : message.substring(0, newline)).trim();
      String messageBody = newline < 0 ? "" : message.substring(newline + 1).trim();
      commits.add(new MainCommit(listed.sha(), committed, subject, messageBody, List.of()));
    }
    return List.copyOf(commits);
  }

  /**
   * The last listing and the ETag GitHub sent with it.
   *
   * @param etag the validator to send back, or null when GitHub sent none
   * @param commits the parsed commits
   */
  private record CachedListing(String etag, List<MainCommit> commits) {
  }

  /** One entry of {@code GET /repos/{repo}/commits}. */
  private record ListedCommit(String sha, CommitData commit) {
  }

  /** The git-level part of a listed commit. */
  private record CommitData(String message, Signature committer) {
  }

  /**
   * Committer identity and time. The committer date, not the author date: on a squash merge
   * the committer is GitHub at merge time, which is when the change reached {@code main}.
   */
  private record Signature(Instant date) {
  }

  /** {@code GET /repos/{repo}/commits/{sha}}, reduced to the file list. */
  private record CommitDetail(List<ChangedFile> files) {
  }

  /** One changed path. */
  private record ChangedFile(String filename) {
  }
}
