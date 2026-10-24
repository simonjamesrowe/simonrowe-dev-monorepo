package com.simonrowe.factory.ticketagent.github;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.CheckRunGateway;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.git.DependencyPins;
import com.simonrowe.factory.git.NpmLockPins;
import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import com.simonrowe.factory.ticketagent.domain.ChecksReport;
import com.simonrowe.factory.ticketagent.domain.PullRequestRef;
import com.simonrowe.factory.ticketagent.domain.PullRequestState;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything the ticket agent asks of GitHub.
 *
 * <p>Duplicates the small {@code HttpClient} plumbing in the other gateways rather than sharing it,
 * as they do with each other: each gateway serves one module and evolves with it.
 *
 * <p>The client never follows redirects. The one endpoint that redirects is the job log download,
 * which answers with a signed blob-storage URL; that hop is validated by origin and fetched without
 * the GitHub token, so a redirect can neither leak the token nor point the factory somewhere else.
 */
@Component
public class TicketAgentGitHub {

  private static final Logger LOGGER = LoggerFactory.getLogger(TicketAgentGitHub.class);
  private static final String API_VERSION = "2026-03-10";
  private static final String USER_AGENT = "simonrowe-ticket-agent";
  private static final int MAX_LINE_CHARS = 400;
  private static final int MAX_MATCHED_LINES = 40;
  private static final String DEPENDENCY_REVIEW_NOTE =
      """
      Human merge required: this pull request changes build or dependency files. \
      The ticket agent applies `no-auto-merge`; GitHub Actions verifies the change, \
      and a person must merge it.""";
  private static final String VERSION_BUMP_NOTE =
      """
      Vulnerability repair: this pull request only changes dependency versions, so the \
      reviewer may merge it once GitHub Actions and the review pass.""";

  /** Hosts GitHub sends job log and artifact downloads to. Anything else is not followed. */
  private static final List<String> LOG_HOST_SUFFIXES =
      List.of(".actions.githubusercontent.com", ".blob.core.windows.net");

  private static final String DRAFT_MUTATION =
      """
      mutation($id: ID!) {
        convertPullRequestToDraft(input: {pullRequestId: $id}) { pullRequest { id } }
      }
      """;

  private static final String DISABLE_AUTO_MERGE_MUTATION =
      """
      mutation($id: ID!) {
        disablePullRequestAutoMerge(input: {pullRequestId: $id}) { pullRequest { id } }
      }
      """;

  private final CodeReviewProperties codeReviewProperties;
  private final TicketAgentProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public TicketAgentGitHub(
      final CodeReviewProperties codeReviewProperties,
      final TicketAgentProperties properties,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper) {
    this.codeReviewProperties = codeReviewProperties;
    this.properties = properties;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(codeReviewProperties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** The App installation on the agent's repository. */
  public Long installationId() {
    return credentials.installationId(properties.owner(), properties.repository());
  }

  /**
   * Opens the pull request for a branch, or finds the open one already there.
   *
   * @return the pull request
   */
  public PullRequestRef openPullRequest(
      final String branch, final String title, final String body, final Long installationId) {
    String token = credentials.accessToken(installationId);
    String path = repoPath() + "/pulls";
    ObjectNode payload =
        objectMapper
            .createObjectNode()
            .put("head", branch)
            .put("base", "main")
            .put("title", title)
            .put("body", body);
    HttpResponse<String> created = send("POST", path, payload, token);
    if (created.statusCode() >= 200 && created.statusCode() < 300) {
      return toRef(parse(created));
    }
    if (created.statusCode() != 422) {
      throw failure("POST", path, created);
    }
    String head = URLEncoder.encode(properties.owner() + ":" + branch, StandardCharsets.UTF_8);
    String find = "%s?state=open&head=%s".formatted(path, head);
    HttpResponse<String> found = send("GET", find, null, token);
    JsonNode pulls = parse(requireOk("GET", find, found));
    if (!pulls.isArray() || pulls.isEmpty()) {
      throw new IllegalStateException(
          "GitHub refused to open a pull request for %s and none is open: %s"
              .formatted(branch, truncate(created.body(), 300)));
    }
    return toRef(pulls.get(0));
  }

  /** Adds or removes a label on the pull request. Removing an absent label is not an error. */
  public void setLabel(
      final int number, final String label, final boolean present, final Long installationId) {
    String token = credentials.accessToken(installationId);
    String labels = repoPath() + "/issues/" + number + "/labels";
    if (present) {
      ObjectNode payload = objectMapper.createObjectNode();
      payload.putArray("labels").add(label);
      requireOk("POST", labels, send("POST", labels, payload, token));
      return;
    }
    String path = labels + "/" + URLEncoder.encode(label, StandardCharsets.UTF_8)
        .replace("+", "%20");
    HttpResponse<String> response = send("DELETE", path, null, token);
    if (response.statusCode() != 404) {
      requireOk("DELETE", path, response);
    }
  }

  /** All paths in the full PR, including rename sources and every page of GitHub's file list. */
  public List<String> changedPaths(final int number, final Long installationId) {
    List<String> paths = new ArrayList<>();
    for (DependencyPins.FileDiff file : changedFiles(number, installationId)) {
      paths.add(file.path());
      if (file.previousPath() != null && !file.previousPath().isEmpty()) {
        paths.add(file.previousPath());
      }
    }
    return List.copyOf(paths);
  }

  /** Every file in the full PR with its status and patch, across every page of the listing. */
  public List<DependencyPins.FileDiff> changedFiles(final int number, final Long installationId) {
    String token = credentials.accessToken(installationId);
    List<DependencyPins.FileDiff> diffs = new ArrayList<>();
    // GitHub caps this endpoint at 3,000 files. Fail closed at that cap rather than risk removing
    // no-auto-merge when an unseen file changes a dependency.
    for (int page = 1; page <= 30; page++) {
      String path = "%s/pulls/%d/files?per_page=100&page=%d".formatted(repoPath(), number, page);
      JsonNode files = parse(requireOk("GET", path, send("GET", path, null, token)));
      if (!files.isArray()) {
        throw new IllegalStateException("GitHub pull request files response was not an array");
      }
      for (JsonNode file : files) {
        diffs.add(new DependencyPins.FileDiff(
            file.path("filename").asText(),
            file.hasNonNull("previous_filename") ? file.path("previous_filename").asText() : null,
            file.path("status").asText(""),
            file.hasNonNull("patch") ? file.path("patch").asText() : null));
      }
      if (files.size() < 100) {
        return List.copyOf(diffs);
      }
    }
    throw new IllegalStateException("Pull request file limit reached; manual review required");
  }

  /** The pull request's current head commit. */
  public String headSha(final int number, final Long installationId) {
    String path = repoPath() + "/pulls/" + number;
    JsonNode pull = parse(requireOk("GET", path,
        send("GET", path, null, credentials.accessToken(installationId))));
    return pull.path("head").path("sha").asText("");
  }

  /** The pull request's base commit. */
  public String baseSha(final int number, final Long installationId) {
    String path = repoPath() + "/pulls/" + number;
    JsonNode pull = parse(requireOk("GET", path,
        send("GET", path, null, credentials.accessToken(installationId))));
    return pull.path("base").path("sha").asText("");
  }

  /**
   * The npm lockfile's text at a commit, or null when it cannot be read.
   *
   * <p>Read raw rather than as base64 JSON, which the contents API stops serving at 1 MB; the
   * lockfile was 0.6 MB in October 2026 and only grows. Capped at {@link NpmLockPins#MAX_CHARS}.
   */
  public String lockfileAt(final String ref, final Long installationId) {
    if (ref == null || ref.isBlank()) {
      return null;
    }
    String path = "%s/contents/%s?ref=%s"
        .formatted(repoPath(), NpmLockPins.LOCKFILE,
            URLEncoder.encode(ref, StandardCharsets.UTF_8));
    try {
      HttpResponse<InputStream> response =
          httpClient.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(codeReviewProperties.github().apiBaseUrl() + path))
                  .timeout(codeReviewProperties.github().requestTimeout())
                  .header("Accept", "application/vnd.github.raw+json")
                  .header("X-GitHub-Api-Version", API_VERSION)
                  .header("User-Agent", USER_AGENT)
                  .header("Authorization", "Bearer " + credentials.accessToken(installationId))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        return response.statusCode() == 200
            ? NpmLockPins.text(body.readNBytes(NpmLockPins.MAX_CHARS + 1))
            : null;
      }
    } catch (IOException exception) {
      LOGGER.warn("Could not read {} at {}", NpmLockPins.LOCKFILE, ref, exception);
      return null;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * A pin file's text at a commit, or null when it cannot be read. Only {@link
   * DependencyPins#PIN_FILES} are read, so the path needs no encoding.
   */
  public String pinFileAt(final String file, final String ref, final Long installationId) {
    if (!DependencyPins.isPinFile(file) || ref == null || ref.isBlank()) {
      return null;
    }
    String path = "%s/contents/%s?ref=%s"
        .formatted(repoPath(), file, URLEncoder.encode(ref, StandardCharsets.UTF_8));
    HttpResponse<String> response =
        send("GET", path, null, credentials.accessToken(installationId));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      return null;
    }
    JsonNode body = parse(response);
    if (!"base64".equals(body.path("encoding").asText(""))) {
      return null;
    }
    try {
      return new String(
          Base64.getMimeDecoder().decode(body.path("content").asText("")), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  /** Records, once, that the reviewer may merge this version-only vulnerability repair. */
  public void noteVersionBump(final int number, final Long installationId) {
    appendOnce(number, installationId, VERSION_BUMP_NOTE);
  }

  /** Records the reason for no-auto-merge once, preserving the existing PR description. */
  public void noteDependencyReview(final int number, final Long installationId) {
    appendOnce(number, installationId, DEPENDENCY_REVIEW_NOTE);
  }

  private void appendOnce(final int number, final Long installationId, final String note) {
    String token = credentials.accessToken(installationId);
    String path = repoPath() + "/pulls/" + number;
    JsonNode pull = parse(requireOk("GET", path, send("GET", path, null, token)));
    String body = pull.path("body").asText("");
    if (!body.contains(note)) {
      ObjectNode payload = objectMapper.createObjectNode().put("body", body + "\n\n" + note);
      requireOk("PATCH", path, send("PATCH", path, payload, token));
    }
  }

  /** The pull request as GitHub reports it now. */
  public PullRequestState readPullRequest(final int number, final Long installationId) {
    String path = repoPath() + "/pulls/" + number;
    String token = credentials.accessToken(installationId);
    JsonNode pull = parse(requireOk("GET", path, send("GET", path, null, token)));
    return new PullRequestState(
        "open".equals(pull.path("state").asText()),
        pull.path("merged").asBoolean(false),
        pull.path("auto_merge").isObject());
  }

  /**
   * Both verdicts on a commit: the CI workflow's and the {@code Code Review} check's.
   *
   * <p>Read directly, as the fallback for a webhook that never arrived and to learn the reviewer's
   * verdict, which no webhook this App receives reports.
   */
  public ChecksReport checks(final String headSha, final Long installationId) {
    String token = credentials.accessToken(installationId);
    String runsPath = repoPath() + "/actions/runs?head_sha=" + headSha + "&per_page=50";
    JsonNode runs = parse(requireOk("GET", runsPath, send("GET", runsPath, null, token)));
    boolean ciComplete = false;
    boolean ciPassed = false;
    long ciRunId = 0;
    // The newest run of the CI workflow for this commit: a re-run supersedes the first.
    for (JsonNode run : runs.path("workflow_runs")) {
      if (!properties.ciWorkflowName().equals(run.path("name").asText())) {
        continue;
      }
      long id = run.path("id").asLong();
      if (id > ciRunId) {
        ciRunId = id;
        ciComplete = "completed".equals(run.path("status").asText());
        ciPassed = ciComplete && "success".equals(run.path("conclusion").asText());
      }
    }
    String checkPath = reviewCheckPath(headSha);
    JsonNode checkRuns = parse(requireOk("GET", checkPath, send("GET", checkPath, null, token)));
    boolean reviewComplete = false;
    boolean reviewPassed = false;
    long newest = 0;
    for (JsonNode check : checkRuns.path("check_runs")) {
      long id = check.path("id").asLong();
      if (id > newest) {
        newest = id;
        reviewComplete = "completed".equals(check.path("status").asText());
        reviewPassed = reviewComplete && "success".equals(check.path("conclusion").asText());
      }
    }
    return new ChecksReport(headSha, ciComplete, ciPassed, ciRunId, reviewComplete, reviewPassed);
  }

  /**
   * What went wrong, for the next attempt's brief: failed jobs with their failed steps and log
   * tails, and the reviewer's verdict and findings when its check was red.
   */
  public String failureBrief(
      final ChecksReport checks, final int pullNumber, final Long installationId) {
    String token = credentials.accessToken(installationId);
    StringBuilder brief = new StringBuilder();
    if (checks.ciRunId() > 0 && !checks.ciPassed()) {
      appendFailedJobs(brief, checks.ciRunId(), token);
    }
    if (checks.reviewComplete() && !checks.reviewPassed()) {
      appendReview(brief, checks.headSha(), pullNumber, token);
    }
    if (brief.isEmpty()) {
      brief.append("CI did not report a result for ").append(checks.headSha())
          .append(" in time, so nothing more specific is known.");
    }
    return brief.toString();
  }

  /** Marks the pull request a draft and withdraws any armed auto-merge. Best effort. */
  public void park(final String nodeId, final Long installationId) {
    String token = credentials.accessToken(installationId);
    for (String mutation : List.of(DISABLE_AUTO_MERGE_MUTATION, DRAFT_MUTATION)) {
      try {
        ObjectNode payload = objectMapper.createObjectNode().put("query", mutation);
        payload.putObject("variables").put("id", nodeId);
        send("POST", "/graphql", payload, token);
      } catch (IllegalStateException exception) {
        LOGGER.warn("Could not park pull request {}", nodeId, exception);
      }
    }
  }

  private void appendFailedJobs(final StringBuilder brief, final long runId, final String token) {
    String path = repoPath() + "/actions/runs/" + runId + "/jobs?per_page=100";
    JsonNode jobs = parse(requireOk("GET", path, send("GET", path, null, token)));
    for (JsonNode job : jobs.path("jobs")) {
      String conclusion = job.path("conclusion").asText("");
      if (!"failure".equals(conclusion) && !"timed_out".equals(conclusion)) {
        continue;
      }
      brief.append("## CI job failed: ").append(job.path("name").asText()).append('\n');
      for (JsonNode step : job.path("steps")) {
        if ("failure".equals(step.path("conclusion").asText())) {
          brief.append("Failed step: ").append(step.path("name").asText()).append('\n');
        }
      }
      brief.append(jobLog(job.path("id").asLong(), token)).append("\n\n");
    }
  }

  private void appendReview(
      final StringBuilder brief, final String headSha, final int pullNumber, final String token) {
    brief.append("## The code reviewer's check was red\n");
    String checkPath = reviewCheckPath(headSha);
    JsonNode checkRuns = parse(requireOk("GET", checkPath, send("GET", checkPath, null, token)));
    // The newest run, as in checks(): a re-review replaces the verdict it is reporting on.
    JsonNode newest = null;
    for (JsonNode check : checkRuns.path("check_runs")) {
      if (newest == null || check.path("id").asLong() > newest.path("id").asLong()) {
        newest = check;
      }
    }
    if (newest != null) {
      JsonNode output = newest.path("output");
      brief.append(output.path("title").asText("")).append('\n')
          .append(truncate(output.path("summary").asText(""), 4_000)).append('\n');
    }
    String bot = credentials.appBotLogin();
    String commentsPath = repoPath() + "/pulls/" + pullNumber + "/comments?per_page=100";
    JsonNode comments =
        parse(requireOk("GET", commentsPath, send("GET", commentsPath, null, token)));
    for (JsonNode comment : comments) {
      if (bot != null && bot.equals(comment.path("user").path("login").asText())
          && headSha.equals(comment.path("commit_id").asText())) {
        brief.append("\n### ").append(comment.path("path").asText())
            .append(':').append(comment.path("line").asText("?")).append('\n')
            .append(truncate(comment.path("body").asText(""), 2_000)).append('\n');
      }
    }
  }

  /**
   * The tail of a job's log, and the lines in it that look like the failure.
   *
   * <p>Logs need the App's {@code actions: read}, an optional permission: without it the brief
   * says so and carries the job and step names alone, which is a weaker brief, not a failure.
   */
  private String jobLog(final long jobId, final String token) {
    String path = repoPath() + "/actions/jobs/" + jobId + "/logs";
    HttpResponse<String> redirect = send("GET", path, null, token);
    if (redirect.statusCode() == 403 || redirect.statusCode() == 404) {
      return "(The job log is not readable: grant the GitHub App `actions: read` to include it.)";
    }
    Optional<URI> location =
        redirect.headers().firstValue("location").flatMap(TicketAgentGitHub::logLocation);
    if (redirect.statusCode() / 100 != 3 || location.isEmpty()) {
      return "(The job log could not be fetched: GitHub answered " + redirect.statusCode() + ".)";
    }
    try {
      HttpResponse<Stream<String>> log =
          httpClient.send(
              HttpRequest.newBuilder(location.get())
                  .timeout(codeReviewProperties.github().requestTimeout())
                  .header("User-Agent", USER_AGENT)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofLines());
      if (log.statusCode() != 200) {
        log.body().close();
        return "(The job log download answered " + log.statusCode() + ".)";
      }
      try (Stream<String> lines = log.body()) {
        return summariseLog(lines.iterator(), properties.logLines());
      }
    } catch (IOException exception) {
      return "(The job log could not be read: " + exception.getMessage() + ")";
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return "(Interrupted reading the job log.)";
    }
  }

  /**
   * Keeps the last {@code tail} lines, plus earlier lines that name a failure.
   *
   * <p>One pass, constant memory, plain {@code contains} on each line: a log is unbounded input.
   */
  static String summariseLog(final Iterator<String> lines, final int tail) {
    Deque<String> last = new ArrayDeque<>();
    List<String> matched = new ArrayList<>();
    while (lines.hasNext()) {
      String line = clean(lines.next());
      if (last.size() == tail) {
        String evicted = last.removeFirst();
        if (matched.size() < MAX_MATCHED_LINES && namesFailure(evicted)) {
          matched.add(evicted);
        }
      }
      last.addLast(line);
    }
    StringBuilder out = new StringBuilder();
    if (!matched.isEmpty()) {
      out.append("Earlier lines naming a failure:\n");
      matched.forEach(line -> out.append(line).append('\n'));
      out.append("...\n");
    }
    out.append("Last ").append(last.size()).append(" lines:\n");
    last.forEach(line -> out.append(line).append('\n'));
    return out.toString();
  }

  private static boolean namesFailure(final String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    return lower.contains(" failed") || lower.contains("error:") || lower.contains("[error]")
        || lower.contains("exception") || lower.contains("assertionerror")
        || lower.contains("expected:");
  }

  /** Drops the runner's timestamp prefix and caps the line. */
  private static String clean(final String line) {
    String value = line;
    // "2026-10-19T12:00:00.0000000Z text": an ISO instant, then a space. Found by the space and
    // checked by shape, so the fraction's length does not matter.
    int space = value.indexOf(' ');
    if (space >= 20 && space <= 40 && value.charAt(4) == '-' && value.charAt(10) == 'T'
        && value.charAt(space - 1) == 'Z') {
      value = value.substring(space + 1);
    }
    return value.length() <= MAX_LINE_CHARS ? value : value.substring(0, MAX_LINE_CHARS) + "...";
  }

  /**
   * Only an https redirect to a host GitHub stores logs and artifacts on is followed, and always
   * without the GitHub token.
   */
  public static Optional<URI> logLocation(final String location) {
    try {
      URI uri = new URI(location);
      String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      boolean allowed = LOG_HOST_SUFFIXES.stream().anyMatch(host::endsWith);
      return "https".equalsIgnoreCase(uri.getScheme()) && allowed
          ? Optional.of(uri)
          : Optional.empty();
    } catch (URISyntaxException exception) {
      return Optional.empty();
    }
  }

  private static PullRequestRef toRef(final JsonNode pull) {
    return new PullRequestRef(
        pull.path("number").asInt(), pull.path("html_url").asText(), pull.path("node_id").asText());
  }

  /** The commit's {@code Code Review} check runs; the name is encoded with %20, not +. */
  private String reviewCheckPath(final String headSha) {
    String name =
        URLEncoder.encode(CheckRunGateway.CHECK_NAME, StandardCharsets.UTF_8).replace("+", "%20");
    return "%s/commits/%s/check-runs?check_name=%s".formatted(repoPath(), headSha, name);
  }

  private String repoPath() {
    return "/repos/" + properties.owner() + "/" + properties.repository();
  }

  private JsonNode parse(final HttpResponse<String> response) {
    try {
      String body = response.body();
      return body == null || body.isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(body);
    } catch (JacksonException exception) {
      throw new IllegalStateException("GitHub response was not valid JSON", exception);
    }
  }

  private static HttpResponse<String> requireOk(
      final String method, final String path, final HttpResponse<String> response) {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw failure(method, path, response);
    }
    return response;
  }

  private static IllegalStateException failure(
      final String method, final String path, final HttpResponse<String> response) {
    return new IllegalStateException(
        "GitHub returned %d for %s %s: %s"
            .formatted(response.statusCode(), method, path, truncate(response.body(), 300)));
  }

  private static String truncate(final String text, final int max) {
    if (text == null) {
      return "";
    }
    return text.length() <= max ? text : text.substring(0, max) + "...";
  }

  private HttpResponse<String> send(
      final String method, final String path, final JsonNode payload, final String token) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(codeReviewProperties.github().apiBaseUrl() + path))
              .timeout(codeReviewProperties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", USER_AGENT);
      if (token != null && !token.isBlank()) {
        request.header("Authorization", "Bearer " + token);
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
    } catch (IOException | JacksonException exception) {
      throw new IllegalStateException("GitHub request failed", exception);
    }
  }
}
