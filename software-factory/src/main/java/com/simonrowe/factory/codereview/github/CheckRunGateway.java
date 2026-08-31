package com.simonrowe.factory.codereview.github;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.CheckRunConclusion;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.Severity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Publishes the review verdict as a {@code Code Review} check run.
 *
 * <p>The verdict used to be published as an issue comment, which no merge path can read — so the
 * reviewer could not gate anything, and a failed review commonly posted nothing at all. A check run
 * is a first-class commit status a ruleset can require by name, which turns both of those around:
 * a critical finding is hard-red, and an outage is an <em>absent</em> required check, which blocks.
 *
 * <p><b>This is the one part of the feature that needed a new App permission.</b> {@link
 * GitHubCredentials#accessToken} requests an explicit permission set including {@code checks:
 * write}, and GitHub 422s the entire token request when it asks for more than the installation was
 * granted. Deploying an image that requests it before the grant lands therefore breaks every token
 * mint, taking down code review <em>and</em> the feedback loop. See the rollout order in
 * {@code docs/runbooks/pr-governance.md}.
 */
@Component
public class CheckRunGateway {

  /** The name a ruleset requires. Changing it silently un-gates the default branch. */
  public static final String CHECK_NAME = "Code Review";

  private static final String API_VERSION = "2026-03-10";

  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Clock clock;

  // Two constructors, so the injection point has to be named: without this Spring finds no
  // default constructor and the whole context fails to start. GitHubCredentials carries the
  // same annotation for the same reason.
  @Autowired
  public CheckRunGateway(
      final CodeReviewProperties properties,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper) {
    this(properties, credentials, objectMapper, Clock.systemUTC());
  }

  CheckRunGateway(
      final CodeReviewProperties properties,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper,
      final Clock clock) {
    this.properties = properties;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /**
   * Opens the check run against the commit under review, returning its id.
   *
   * <p>Called once the head SHA is known — which is after the pull request has been loaded, not
   * when the status comment is opened. At that earlier point only a {@code ReviewRequest} exists,
   * and its {@code expectedHeadSha} is nullable on the manual-review path, so there may be no
   * commit to attach a check to.
   */
  public String open(final PullRequestContext pullRequest, final String workflowId) {
    ObjectNode output =
        objectMapper
            .createObjectNode()
            .put("title", "Review in progress")
            .put("summary", "An automated review of these changes is running.");
    ObjectNode payload =
        objectMapper
            .createObjectNode()
            .put("name", CHECK_NAME)
            .put("head_sha", pullRequest.headSha())
            .put("status", "in_progress")
            .put("started_at", now());
    String detailsUrl = detailsUrl(workflowId);
    if (detailsUrl != null) {
      payload.put("details_url", detailsUrl);
    }
    payload.set("output", output);

    JsonNode response =
        sendJson(
            "POST",
            checkRunsPath(pullRequest.owner(), pullRequest.repository()),
            payload,
            pullRequest);
    String id = response.path("id").asText();
    if (id.isBlank()) {
      throw new IllegalStateException("GitHub response omitted the check run id");
    }
    return id;
  }

  /** Completes the check run for a review that produced a report. */
  public void complete(
      final PullRequestContext pullRequest, final String checkRunId, final ReviewReport report) {
    CheckRunConclusion conclusion =
        CheckRunConclusion.from(report.verdict(), report.findings());
    complete(pullRequest, checkRunId, conclusion, completedTitle(report), report.summary());
  }

  private void complete(
      final PullRequestContext pullRequest,
      final String checkRunId,
      final CheckRunConclusion conclusion,
      final String title,
      final String summary) {
    ObjectNode output =
        objectMapper
            .createObjectNode()
            .put("title", title)
            .put("summary", summary == null || summary.isBlank() ? title : summary);
    ObjectNode payload =
        objectMapper
            .createObjectNode()
            .put("status", "completed")
            .put("conclusion", conclusion.toJson())
            .put("completed_at", now());
    payload.set("output", output);

    sendJson(
        "PATCH",
        checkRunPath(pullRequest.owner(), pullRequest.repository(), checkRunId),
        payload,
        pullRequest);
  }

  /**
   * Completes the check run {@code failure} for a review that did not produce a report.
   *
   * <p>Only reachable once a check run exists. A review that died before the head SHA was known has
   * no check run to fail, and must not gain one: its <em>absence</em> is what blocks the merge.
   */
  public void fail(
      final PullRequestContext pullRequest,
      final String checkRunId,
      final ReviewFailure failure) {
    complete(
        pullRequest,
        checkRunId,
        CheckRunConclusion.FAILURE,
        "Review failed in " + failure.phase(),
        failureSummary(failure));
  }

  static String completedTitle(final ReviewReport report) {
    long critical =
        report.findings().stream()
            .filter(finding -> finding.severity() == Severity.CRITICAL)
            .count();
    int total = report.findings().size();
    String verdict = report.verdict().toJson();
    if (critical > 0) {
      return verdict + " — " + critical + " critical of " + total + " finding(s)";
    }
    return total == 0 ? verdict + " — no findings" : verdict + " — " + total + " finding(s)";
  }

  private String failureSummary(final ReviewFailure failure) {
    StringBuilder summary = new StringBuilder();
    summary
        .append("This review did not complete, so these changes have **not** been reviewed.\n\n")
        .append("**Phase:** `")
        .append(failure.phase())
        .append("`\n\n")
        .append(failure.reason() == null || failure.reason().isBlank()
            ? "No failure detail was reported."
            : failure.reason());
    String detailsUrl = detailsUrl(failure.workflowId());
    if (detailsUrl != null) {
      summary.append("\n\n[Workflow history](").append(detailsUrl).append(')');
    }
    return summary.toString();
  }

  /**
   * The Temporal deep link, or null when there is nothing to link.
   *
   * <p>Same construction and same unconfigured-base tolerance as the status comment's link: an
   * unset base URL must never cost the reader the conclusion itself.
   */
  private String detailsUrl(final String workflowId) {
    String base = properties.temporalUiBaseUrl();
    if (workflowId == null || workflowId.isBlank() || base == null || base.isBlank()) {
      return null;
    }
    String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    return trimmed + "/namespaces/default/workflows/" + workflowId;
  }

  static String checkRunsPath(final String owner, final String repository) {
    return "/repos/" + owner + "/" + repository + "/check-runs";
  }

  static String checkRunPath(
      final String owner, final String repository, final String checkRunId) {
    return "/repos/" + owner + "/" + repository + "/check-runs/" + checkRunId;
  }

  private String now() {
    return DateTimeFormatter.ISO_INSTANT.format(clock.instant());
  }

  private JsonNode sendJson(
      final String method,
      final String path,
      final ObjectNode payload,
      final PullRequestContext pullRequest) {
    try {
      String accessToken = credentials.accessToken(pullRequest.installationId());
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.github().apiBaseUrl() + path))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer")
              .header("Content-Type", "application/json")
              .method(
                  method,
                  HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
      if (accessToken != null && !accessToken.isBlank()) {
        request.header("Authorization", "Bearer " + accessToken);
      }

      HttpResponse<String> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub API returned " + response.statusCode() + " for " + method + " " + path);
      }
      return response.body().isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub check run request interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("GitHub check run request failed", exception);
    }
  }
}
