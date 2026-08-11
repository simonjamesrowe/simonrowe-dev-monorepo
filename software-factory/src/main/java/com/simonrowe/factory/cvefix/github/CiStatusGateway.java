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
 * their own check-runs request. Both figures sit under the 60/hour ceiling. Do not lower that
 * interval and do not add auth to this gateway to raise the ceiling — if more headroom is ever
 * needed, that is a call to widen the shared token's permissions deliberately, not a side effect
 * of this class.
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
   * @return {@link CiState#PENDING} if no non-advisory check has registered yet or any is still
   *     running, {@link CiState#GREEN} if every non-advisory check passed, otherwise {@link
   *     CiState#RED} naming the non-advisory checks that failed
   * @throws IllegalStateException if the request fails or returns a non-2xx status
   */
  public CiOutcome outcomeFor(final String headSha) {
    List<JsonNode> relevant = relevantCheckRuns(headSha);
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
   * @param headSha the commit sha to look up check runs for
   * @return the concatenated {@code output.summary} and {@code output.text} of each failed
   *     non-advisory check run, truncated to about {@value #MAX_FAILURE_LOG_CHARACTERS} characters
   * @throws IllegalStateException if the request fails or returns a non-2xx status
   */
  public String failureLogs(final String headSha) {
    StringBuilder logs = new StringBuilder();
    for (JsonNode run : relevantCheckRuns(headSha)) {
      if (!isCompleted(run) || PASSING_CONCLUSIONS.contains(run.path("conclusion").asText(""))) {
        continue;
      }
      logs.append("### ").append(run.path("name").asText("")).append('\n');
      logs.append(run.path("output").path("summary").asText("")).append('\n');
      logs.append(run.path("output").path("text").asText("")).append("\n\n");
    }
    return logs.length() > MAX_FAILURE_LOG_CHARACTERS
        ? logs.substring(0, MAX_FAILURE_LOG_CHARACTERS)
        : logs.toString();
  }

  private List<JsonNode> relevantCheckRuns(final String headSha) {
    // per_page=100 is deliberate and load-bearing: GitHub pages this endpoint at 30 by default, so
    // without it a commit carrying more than 30 checks would return only the first page and an
    // all-passing first page would read GREEN while a later-page check failed — a false green, the
    // one failure this gateway must never produce. 100 is the documented ceiling for this
    // endpoint, so a commit with more than 100 check runs would reintroduce that risk; if the
    // workflow set ever approaches that many checks, follow the Link rel="next" header rather than
    // raising this number.
    JsonNode payload =
        get(
            "/repos/"
                + properties.owner()
                + "/"
                + properties.repository()
                + "/commits/"
                + headSha
                + "/check-runs?per_page=100");
    List<String> advisoryChecks = properties.ci().advisoryChecks();
    List<JsonNode> relevant = new ArrayList<>();
    for (JsonNode run : payload.path("check_runs")) {
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
