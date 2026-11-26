package com.simonrowe.factory.codereview.github;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.CheckRunConclusion;
import com.simonrowe.factory.codereview.domain.FindingFingerprint;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.Severity;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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

  /**
   * Starts the hidden list of fingerprints that failed a completed review's check run. Only a run
   * carrying it can be turned green by a decline: a failed review writes none.
   */
  static final String GATING_PREFIX = "<!-- code-review-gating:";

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
    complete(
        pullRequest,
        checkRunId,
        conclusion,
        completedTitle(report),
        report.summary(),
        gatingMarker(gatingFingerprints(report)));
  }

  private void complete(
      final PullRequestContext pullRequest,
      final String checkRunId,
      final CheckRunConclusion conclusion,
      final String title,
      final String summary,
      final String text) {
    ObjectNode payload = completedPayload(conclusion, title, summary, text);
    sendJson(
        "PATCH",
        checkRunPath(pullRequest.owner(), pullRequest.repository(), checkRunId),
        payload,
        pullRequest);
  }

  private ObjectNode completedPayload(
      final CheckRunConclusion conclusion,
      final String title,
      final String summary,
      final String text) {
    ObjectNode output =
        objectMapper
            .createObjectNode()
            .put("title", title)
            .put("summary", summary == null || summary.isBlank() ? title : summary);
    if (text != null) {
      output.put("text", text);
    }
    ObjectNode payload =
        objectMapper
            .createObjectNode()
            .put("status", "completed")
            .put("conclusion", conclusion.toJson())
            .put("completed_at", now());
    payload.set("output", output);
    return payload;
  }

  /**
   * The latest {@code Code Review} check run on the pull request's head, or empty when there is
   * none this reviewer wrote.
   */
  public Optional<Gate> latest(final PullRequestContext pullRequest) {
    JsonNode runs =
        sendJson(
            "GET",
            "/repos/%s/%s/commits/%s/check-runs?check_name=%s&filter=latest"
                .formatted(
                    pullRequest.owner(),
                    pullRequest.repository(),
                    pullRequest.headSha(),
                    URLEncoder.encode(CHECK_NAME, StandardCharsets.UTF_8).replace("+", "%20")),
            null,
            pullRequest)
            .path("check_runs");
    Gate latest = null;
    for (JsonNode run : runs) {
      Gate gate = toGate(run);
      if (gate != null && (latest == null || gate.id() > latest.id())) {
        latest = gate;
      }
    }
    return Optional.ofNullable(latest);
  }

  /** Maps one check run, or null when it is not a {@code Code Review} run. Pure, for tests. */
  static Gate toGate(final JsonNode run) {
    if (!CHECK_NAME.equals(run.path("name").asText(""))) {
      return null;
    }
    return new Gate(
        run.path("id").asLong(),
        "completed".equals(run.path("status").asText("")),
        run.path("conclusion").asText(""),
        parseGating(run.path("output").path("text").asText("")));
  }

  /**
   * Turns a failed check green because a person declined every finding that failed it.
   *
   * <p>A new completed run rather than an edit of the old one: GitHub gates on the latest run of a
   * name, and the failed run stays in the history beside the reason it no longer holds.
   */
  public void declined(final PullRequestContext pullRequest, final List<String> declinedBy) {
    String people =
        declinedBy.isEmpty() ? "a maintainer" : "@" + String.join(", @", declinedBy);
    String title = "approve — critical findings declined";
    ObjectNode payload =
        completedPayload(
            CheckRunConclusion.SUCCESS,
            title,
            """
            Every critical finding that failed this check was declined by %s, by resolving its \
            conversation. Auto-merge is not armed by this; merge by hand, or push to re-review."""
                .formatted(people),
            gatingMarker(Set.of()));
    payload.put("name", CHECK_NAME).put("head_sha", pullRequest.headSha());
    sendJson(
        "POST", checkRunsPath(pullRequest.owner(), pullRequest.repository()), payload, pullRequest);
  }

  /**
   * The fingerprints of the findings that fail a report's check: its critical ones.
   *
   * <p>Written into the check run so a later decline can tell, from GitHub alone, whether every
   * reason for the failure has gone. That includes a critical finding that never got a thread
   * because its line did not anchor: it cannot be resolved, so it keeps the check red.
   */
  static Set<String> gatingFingerprints(final ReviewReport report) {
    Set<String> gating = new TreeSet<>();
    for (ReviewFinding finding : report.findings()) {
      if (finding.severity() == Severity.CRITICAL) {
        gating.add(FindingFingerprint.of(finding));
      }
    }
    return gating;
  }

  static String gatingMarker(final Set<String> fingerprints) {
    return GATING_PREFIX + String.join(",", new TreeSet<>(fingerprints)) + " -->";
  }

  /** The fingerprints in a check run's text, or null when it carries no gating marker. */
  static Set<String> parseGating(final String text) {
    int start = text == null ? -1 : text.indexOf(GATING_PREFIX);
    if (start < 0) {
      return null;
    }
    int end = text.indexOf(" -->", start);
    if (end < 0) {
      return null;
    }
    Set<String> fingerprints = new TreeSet<>();
    for (String value : text.substring(start + GATING_PREFIX.length(), end).split(",")) {
      String fingerprint = value.strip();
      if (fingerprint.length() == 64) {
        fingerprints.add(fingerprint);
      }
    }
    return fingerprints;
  }

  /**
   * The latest {@code Code Review} run on a commit, as a decline needs it.
   *
   * @param gating the fingerprints that failed it, or null for a run written before they were
   *     recorded, or by a failed review, either of which a decline must never turn green
   */
  public record Gate(long id, boolean completed, String conclusion, Set<String> gating) {
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
        failureSummary(failure),
        null);
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
              .header("User-Agent", "temporal-code-reviewer");
      if (payload == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request
            .header("Content-Type", "application/json")
            .method(
                method,
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
      }
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
    } catch (IOException | JacksonException exception) {
      throw new IllegalStateException("GitHub check run request failed", exception);
    }
  }
}
