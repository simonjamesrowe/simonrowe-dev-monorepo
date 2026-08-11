package com.simonrowe.factory.cvefix.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.CiOutcome.CiState;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads the aggregated CI status for a commit, unauthenticated.
 *
 * <p><strong>This gateway deliberately sends no credential, and that must not be "fixed" later.
 * </strong> {@link com.simonrowe.factory.codereview.github.GitHubCredentials#accessToken} mints
 * one token from a single shared permission payload — {@code contents}, {@code issues} and
 * {@code pull_requests} — that the code-review and feedback flows also depend on. GitHub 422s the
 * *whole* access-token request if the App was not granted a requested permission, so adding
 * {@code checks: read} there to serve this one gateway would break both of those existing flows,
 * not just this one. This repository is public, so {@code GET
 * /repos/{owner}/{repo}/commits/{sha}/check-runs} returns 200 with full check data unauthenticated
 * — verified live against {@code main} at commit {@code 576eeb2}. The cost is the unauthenticated
 * rate limit of 60 requests/hour per IP; {@link CveFixProperties.Ci#pollInterval()} defaults to 3
 * minutes, which keeps usage near 20/hour typical, and near 40/hour worst case while polling a red
 * pull request, because {@link #outcomeFor(String)} and {@link #failureLogs(String)} each issue
 * their own check-runs request. {@link #failureLogs(String)} then adds one annotations request per
 * failed non-advisory check run, capped at {@value #MAX_ANNOTATION_REQUESTS} per call; since the
 * repair budget defaults to 3, it is called at most 3 times per run, so the worst case rises to
 * roughly 20 + 3 × 6 = 38/hour. All three figures sit under the 60/hour ceiling. Do not lower that
 * interval, do not raise {@value #MAX_ANNOTATION_REQUESTS} without redoing that arithmetic, and do
 * not add auth to this gateway to raise the ceiling — if more headroom is ever needed, that is a
 * call to widen the shared token's permissions deliberately, not a side effect of this class.
 *
 * <p><strong>Advisory checks are excluded from the RED decision, and excluded first</strong>,
 * before any GREEN/RED/PENDING determination. {@link CveFixProperties.Ci#advisoryChecks()}
 * defaults to {@code ["evaluate"]}: the promptfoo evals job, which runs with job-level {@code
 * continue-on-error: true}. That setting is documented to stop the *workflow run* from failing;
 * whether it also affects the {@code conclusion} GitHub reports for the job's own check run is
 * <strong>not established</strong> by that documentation, and this class does not assume either
 * way. Observed evidence as of 2026-08-11: no CVE-fix pull request has yet produced a failing
 * {@code evaluate} run to inspect, so the actual conclusion GitHub assigns to a failed
 * continue-on-error job is unverified here. The ignore-list makes the module correct regardless of
 * which way that turns out: if a failing {@code evaluate} run ever does surface as {@code
 * conclusion: failure}, without this exclusion every poll of an otherwise-green pull request would
 * read RED, the agent would burn the entire repair budget trying to fix a promptfoo/OpenAI-spend
 * problem it cannot see, the run would end {@code CI_UNRESOLVED} with the pull request left open,
 * and every later scheduled run would then be skipped via the skip-if-open rule. This gateway must
 * not special-case {@code continue-on-error} in any other way, and must not infer advisory status
 * from a check name's shape — only from the configured {@code advisoryChecks} list.
 */
@Component
public class CiStatusGateway {

  /** Conclusions that do not block a merge. Anything else on a completed run counts as failing. */
  private static final Set<String> PASSING_CONCLUSIONS = Set.of("success", "neutral", "skipped");

  private static final String API_VERSION = "2026-03-10";
  private static final int MAX_FAILURE_LOG_CHARACTERS = 8_000;

  /**
   * Annotation requests one {@link #failureLogs(String)} call will make, at most. One request per
   * failed non-advisory check run, and this repository's workflow set produces at most a handful,
   * so the cap is a backstop against a pathological commit rather than a routine limit.
   */
  private static final int MAX_ANNOTATION_REQUESTS = 5;

  /** Annotations read from any one check run. GitHub's observed counts here are 1 to 3. */
  private static final int MAX_ANNOTATIONS_PER_RUN = 20;

  private final CodeReviewProperties codeReviewProperties;
  private final CveFixProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /**
   * Creates a gateway scoped to the configured CVE-fix owner and repository.
   *
   * @param codeReviewProperties supplies the GitHub API base URL and request timeout
   * @param properties supplies the owner, repository, and the advisory check-run ignore-list
   * @param objectMapper mapper used to parse GitHub's check-runs response
   */
  public CiStatusGateway(
      final CodeReviewProperties codeReviewProperties,
      final CveFixProperties properties,
      final ObjectMapper objectMapper) {
    this.codeReviewProperties = codeReviewProperties;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(codeReviewProperties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /**
   * Reads the aggregated CI outcome for the given commit, ignoring configured advisory checks.
   *
   * @param headSha the commit sha to look up check runs for
   * @return {@link CiState#PENDING} if GitHub returned only part of the check runs it reports, if
   *     no non-advisory check has registered yet, or if any is still running, {@link
   *     CiState#GREEN} if every non-advisory check passed, otherwise {@link CiState#RED} naming the
   *     non-advisory checks that failed
   * @throws IllegalStateException if the request fails or returns a non-2xx status
   */
  public CiOutcome outcomeFor(final String headSha) {
    JsonNode payload = checkRunsPayload(headSha);
    JsonNode allRuns = payload.path("check_runs");
    int reportedCount = payload.path("total_count").asInt(0);
    // Fail safe on partial data. GitHub tells us how many check runs the commit has; if it handed
    // back fewer than that, we are looking at one page of several and any GREEN or RED verdict
    // would be drawn from an unknown subset — an all-passing first page would read GREEN while a
    // failing check sat on a page we never fetched. PENDING costs one more poll cycle; a false
    // GREEN marks an unverified fix as passing, so this must run before advisory filtering and
    // before the state logic, where filtering cannot mask it. Compare against the raw array, not
    // the filtered list: total_count is GitHub's unfiltered count, so comparing it with the
    // post-filter list would fire on every commit that merely has an advisory check.
    if (reportedCount > allRuns.size()) {
      return new CiOutcome(
          CiState.PENDING,
          List.of(),
          "Partial check-run data: GitHub reports "
              + reportedCount
              + " check runs but returned "
              + allRuns.size()
              + "; refusing to decide from one page");
    }

    List<JsonNode> relevant = nonAdvisory(allRuns);
    if (relevant.isEmpty()) {
      return new CiOutcome(
          CiState.PENDING, List.of(), "No non-advisory checks have registered yet");
    }

    boolean anyPending = false;
    List<String> failedNames = new ArrayList<>();
    for (JsonNode run : relevant) {
      if (!isCompleted(run)) {
        anyPending = true;
        continue;
      }
      if (!PASSING_CONCLUSIONS.contains(run.path("conclusion").asText(""))) {
        failedNames.add(run.path("name").asText(""));
      }
    }

    if (anyPending) {
      return new CiOutcome(CiState.PENDING, List.of(), "Waiting for checks to complete");
    }
    if (!failedNames.isEmpty()) {
      return new CiOutcome(
          CiState.RED, failedNames, "Failed checks: " + String.join(", ", failedNames));
    }
    return new CiOutcome(CiState.GREEN, List.of(), "All non-advisory checks passed");
  }

  /**
   * Builds a compact log excerpt for every failed, non-advisory check run on the given commit.
   *
   * <p><strong>Annotations are the substance here, not a garnish.</strong> Verified live against
   * this repository: GitHub Actions check runs return {@code output.summary} and {@code
   * output.text} both null, with {@code annotations_count} between 1 and 3. Reading only those two
   * fields — which this method originally did — produced a heading and two blank lines, so the
   * repair agent received no failure context at all and the repair half of the loop was inert.
   * Both fields are still read for the case where something ever does populate them.
   *
   * <p>Full job logs are not an option: {@code GET /actions/jobs/{id}/logs} needs {@code actions:
   * read} on the installation token, and that token is minted from a single shared permission
   * payload the code-review and feedback flows also depend on — precisely the change this module
   * declines to make (see the class Javadoc). So this channel is intentionally thin, and
   * annotations are the best signal available unauthenticated.
   *
   * @param headSha the commit sha to look up check runs for
   * @return per failed non-advisory check run, its name, any {@code output.summary} and {@code
   *     output.text}, and its annotation messages with level and location; the whole thing
   *     truncated to about {@value #MAX_FAILURE_LOG_CHARACTERS} characters
   * @throws IllegalStateException if the check-runs request fails or returns a non-2xx status. A
   *     failing annotations request does not throw — see {@link #appendAnnotations}.
   */
  public String failureLogs(final String headSha) {
    StringBuilder logs = new StringBuilder();
    int annotationRequests = 0;
    for (JsonNode run : nonAdvisory(checkRunsPayload(headSha).path("check_runs"))) {
      if (!isCompleted(run) || PASSING_CONCLUSIONS.contains(run.path("conclusion").asText(""))) {
        continue;
      }
      logs.append("### ").append(run.path("name").asText("")).append('\n');
      appendIfPresent(logs, run.path("output").path("summary"));
      appendIfPresent(logs, run.path("output").path("text"));
      // Only failed non-advisory runs get an annotations request, and only when GitHub says there
      // is something to fetch: a request per passing check would triple the unauthenticated call
      // count for no signal, and an advisory check's failure is one the agent must not chase.
      if (run.path("annotations_count").asInt(0) > 0
          && annotationRequests < MAX_ANNOTATION_REQUESTS
          && logs.length() < MAX_FAILURE_LOG_CHARACTERS) {
        annotationRequests++;
        appendAnnotations(logs, run.path("id").asLong(0));
      }
      logs.append('\n');
    }
    return logs.length() > MAX_FAILURE_LOG_CHARACTERS
        ? logs.substring(0, MAX_FAILURE_LOG_CHARACTERS)
        : logs.toString();
  }

  private static void appendIfPresent(final StringBuilder logs, final JsonNode node) {
    if (node.isTextual() && !node.asText().isBlank()) {
      logs.append(node.asText()).append('\n');
    }
  }

  /**
   * Appends one check run's annotation messages. Best-effort by design: failure context makes a
   * repair attempt better informed, it is not what makes the run correct, so a 404 or a rate-limit
   * rejection here degrades the prompt rather than failing the activity and burning the attempt.
   */
  private void appendAnnotations(final StringBuilder logs, final long checkRunId) {
    if (checkRunId <= 0) {
      return;
    }
    JsonNode annotations;
    try {
      annotations =
          get(
              "/repos/"
                  + properties.owner()
                  + "/"
                  + properties.repository()
                  + "/check-runs/"
                  + checkRunId
                  + "/annotations?per_page="
                  + MAX_ANNOTATIONS_PER_RUN);
    } catch (RuntimeException exception) {
      return;
    }
    int appended = 0;
    for (JsonNode annotation : annotations) {
      if (appended >= MAX_ANNOTATIONS_PER_RUN || logs.length() >= MAX_FAILURE_LOG_CHARACTERS) {
        return;
      }
      String message = annotation.path("message").asText("");
      if (message.isBlank()) {
        continue;
      }
      logs.append('[').append(annotation.path("annotation_level").asText("unknown")).append("] ");
      String path = annotation.path("path").asText("");
      if (!path.isBlank()) {
        logs.append(path);
        int line = annotation.path("start_line").asInt(0);
        if (line > 0) {
          logs.append(':').append(line);
        }
        logs.append(" - ");
      }
      logs.append(message).append('\n');
      appended++;
    }
  }

  private JsonNode checkRunsPayload(final String headSha) {
    // per_page=100 is deliberate and load-bearing: GitHub pages this endpoint at 30 by default, so
    // without it a commit carrying more than 30 checks would return only the first page. 100 is the
    // documented ceiling for this endpoint, so a commit with more than 100 check runs would still
    // arrive paged — but the total_count shortfall guard in outcomeFor turns that into a safe
    // PENDING rather than a silently wrong verdict, so exceeding it cannot produce a false green.
    // Following the Link rel="next" header is still the eventual fix if the workflow set ever
    // genuinely exceeds 100 checks per commit and PENDING starts to stall runs; until then the
    // guard, not page-following, is what makes this correct.
    return get(
        "/repos/"
            + properties.owner()
            + "/"
            + properties.repository()
            + "/commits/"
            + headSha
            + "/check-runs?per_page=100");
  }

  private List<JsonNode> nonAdvisory(final JsonNode checkRuns) {
    List<String> advisoryChecks = properties.ci().advisoryChecks();
    List<JsonNode> relevant = new ArrayList<>();
    for (JsonNode run : checkRuns) {
      if (!advisoryChecks.contains(run.path("name").asText(""))) {
        relevant.add(run);
      }
    }
    return relevant;
  }

  private static boolean isCompleted(final JsonNode run) {
    return "completed".equals(run.path("status").asText(""));
  }

  private JsonNode get(final String path) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(codeReviewProperties.github().apiBaseUrl() + path))
              .timeout(codeReviewProperties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer")
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
      throw new IllegalStateException("GitHub check-runs request interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("GitHub check-runs request failed", exception);
    }
  }
}
