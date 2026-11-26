package com.simonrowe.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.simonrowe.dashboard.UptimeReport.Incident;
import com.simonrowe.dashboard.UptimeReport.Incidents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the outage issues {@code scripts/prod-heartbeat-alert.sh} opens and closes.
 *
 * <p>The repository is public, so this is anonymous, which caps it at 60 requests an hour per
 * address, shared with {@code GitHubCommitHistory}. So a list is reused for
 * {@link DashboardProperties.Alerts#cacheFor()}, and refreshed with {@code If-None-Match}: a
 * {@code 304} does not count against the limit. When GitHub cannot be read the last list is
 * served; only with nothing cached does the panel say the list is unavailable.
 *
 * <p>The owner and the name are two URI variables, never one: RestClient encodes a slash inside
 * a variable, and GitHub answers {@code owner%2Fname} with a 404 (see
 * {@code GitHubCommitHistory}).
 */
@Component
public class OutageIssues {

  private static final Logger LOG = LoggerFactory.getLogger(OutageIssues.class);

  private final RestClient client;
  private final DashboardProperties.Alerts config;
  private final Clock clock;
  private final String owner;
  private final String name;
  private final AtomicReference<Cached> cache = new AtomicReference<>(null);

  /**
   * Creates the reader.
   *
   * @param properties dashboard settings
   * @param clock the time source
   */
  public OutageIssues(final DashboardProperties properties, final Clock clock) {
    this.config = properties.alerts();
    this.clock = clock;
    int slash = config.repository().indexOf('/');
    if (slash <= 0 || slash == config.repository().length() - 1) {
      throw new IllegalArgumentException(
          "dashboard.alerts.repository must be owner/name, was: " + config.repository());
    }
    this.owner = config.repository().substring(0, slash);
    this.name = config.repository().substring(slash + 1);
    this.client = RestClient.builder()
        .baseUrl(config.apiBaseUrl())
        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .defaultHeader(HttpHeaders.USER_AGENT, "simonrowe-dev-backend")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
        .requestFactory(new RestTemplateBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(10))
            .buildRequestFactory())
        .build();
  }

  /**
   * The most recent outage issues, newest first.
   *
   * @return the issues, or {@code available=false} when they could never be read
   */
  public Incidents recent() {
    Cached cached = cache.get();
    Instant now = clock.instant();
    if (cached != null && cached.fetchedAt().plus(config.cacheFor()).isAfter(now)) {
      return new Incidents(true, cached.items());
    }
    try {
      Cached fetched = fetch(cached, now);
      cache.set(fetched);
      return new Incidents(true, fetched.items());
    } catch (RestClientException e) {
      LOG.warn("Could not read outage issues: {}", e.getMessage());
      return cached == null ? new Incidents(false, List.of()) : new Incidents(true, cached.items());
    }
  }

  private Cached fetch(final Cached cached, final Instant now) {
    return client.get()
        .uri("/repos/{owner}/{name}/issues?labels={label}&state=all&per_page=30",
            owner, name, config.label())
        .headers(headers -> {
          if (cached != null && cached.etag() != null) {
            headers.setIfNoneMatch(cached.etag());
          }
        })
        .exchange((request, response) -> {
          if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED) && cached != null) {
            return new Cached(cached.etag(), cached.items(), now);
          }
          if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RestClientException(
                "GitHub issue listing returned " + response.getStatusCode().value());
          }
          Issue[] body = response.bodyTo(Issue[].class);
          return new Cached(response.getHeaders().getETag(), toIncidents(body), now);
        });
  }

  private static List<Incident> toIncidents(final Issue[] body) {
    // Kept although a 200 promises a body: an empty 200 from a proxy must not NPE the panel.
    if (body == null) {
      return List.of();
    }
    List<Incident> items = new ArrayList<>();
    for (Issue issue : body) {
      if (issue == null || issue.createdAt() == null || issue.pullRequest() != null) {
        continue;
      }
      items.add(new Incident(issue.number(), issue.title() == null ? "" : issue.title(),
          issue.createdAt(), issue.closedAt(), issue.htmlUrl()));
    }
    return List.copyOf(items);
  }

  /**
   * A fetched list and the validator GitHub sent with it.
   *
   * @param etag the validator, or null
   * @param items the issues
   * @param fetchedAt when GitHub was last asked
   */
  private record Cached(String etag, List<Incident> items, Instant fetchedAt) {
  }

  /**
   * One entry of {@code GET /repos/{repo}/issues}. The listing includes pull requests, which
   * carry {@code pull_request}; none should have the outage label, but they are skipped anyway.
   */
  private record Issue(
      int number,
      String title,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("closed_at") Instant closedAt,
      @JsonProperty("html_url") String htmlUrl,
      @JsonProperty("pull_request") Object pullRequest
  ) {
  }
}
