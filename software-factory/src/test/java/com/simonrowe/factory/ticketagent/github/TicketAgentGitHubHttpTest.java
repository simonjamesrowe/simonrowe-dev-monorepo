package com.simonrowe.factory.ticketagent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.testsupport.TicketAgentTestProperties;
import com.simonrowe.factory.ticketagent.domain.ChecksReport;
import com.simonrowe.factory.ticketagent.domain.PullRequestRef;
import com.simonrowe.factory.ticketagent.domain.PullRequestState;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The gateway against a stub GitHub: every call it makes, and how it reads the answers. */
class TicketAgentGitHubHttpTest {

  private static final String REPO = "/repos/example/project";
  private static final String BOT = "simonrowe-software-factory[bot]";

  private record Reply(int status, String body, Map<String, String> headers) {
  }

  private final Map<String, Reply> replies = new ConcurrentHashMap<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private TicketAgentGitHub gitHub;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String query = exchange.getRequestURI().getRawQuery();
      String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
          + (query == null ? "" : "?" + query);
      requests.add(key);
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      Reply reply = replies.getOrDefault(key, new Reply(404, "{}", Map.of()));
      reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
      byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
      if (out.length > 0) {
        exchange.getResponseBody().write(out);
      }
      exchange.close();
    });
    server.start();
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.accessToken(any())).thenReturn("token");
    when(credentials.appBotLogin()).thenReturn(BOT);
    when(credentials.installationId("example", "project")).thenReturn(7L);
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "", "", "",
                Duration.ofSeconds(5)),
            null, new CodeReviewProperties.Api("", null), null);
    gitHub = new TicketAgentGitHub(
        properties, TicketAgentTestProperties.of(true, "example", "project"), credentials,
        new ObjectMapper());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void reply(final String key, final int status, final String body) {
    replies.put(key, new Reply(status, body, Map.of()));
  }

  @Test
  void resolvesTheInstallationOnItsOwnRepository() {
    assertThat(gitHub.installationId()).isEqualTo(7L);
  }

  @Test
  void opensPullRequestAgainstMain() {
    reply("POST " + REPO + "/pulls", 201,
        """
        {"number":271,"html_url":"https://github.com/example/project/pull/271",\
        "node_id":"PR_271"}""");

    PullRequestRef ref = gitHub.openPullRequest("agent/sim-1", "fix: x", "Body", 7L);

    assertThat(ref).isEqualTo(
        new PullRequestRef(271, "https://github.com/example/project/pull/271", "PR_271"));
    assertThat(bodies.get(0)).contains("\"head\":\"agent/sim-1\"").contains("\"base\":\"main\"");
  }

  @Test
  void findsTheOpenPullRequestWhenOneAlreadyExists() {
    reply("POST " + REPO + "/pulls", 422, "{\"message\":\"A pull request already exists\"}");
    reply("GET " + REPO + "/pulls?state=open&head=example%3Aagent%2Fsim-1", 200,
        "[{\"number\":12,\"html_url\":\"u\",\"node_id\":\"PR_12\"}]");

    assertThat(gitHub.openPullRequest("agent/sim-1", "fix: x", "Body", 7L).number())
        .isEqualTo(12);
  }

  @Test
  void refusesWhenGitHubRejectsAndNothingIsOpen() {
    reply("POST " + REPO + "/pulls", 422, "{\"message\":\"No commits between\"}");
    reply("GET " + REPO + "/pulls?state=open&head=example%3Aagent%2Fsim-1", 200, "[]");

    assertThatThrownBy(() -> gitHub.openPullRequest("agent/sim-1", "t", "b", 7L))
        .hasMessageContaining("No commits between");
  }

  @Test
  void anyOtherRefusalIsAnError() {
    reply("POST " + REPO + "/pulls", 500, "{}");

    assertThatThrownBy(() -> gitHub.openPullRequest("agent/sim-1", "t", "b", 7L))
        .hasMessageContaining("GitHub returned 500");
  }

  @Test
  void addsAndRemovesLabelAndAnAbsentOneIsAlreadyGone() {
    reply("POST " + REPO + "/issues/271/labels", 200, "[]");
    reply("DELETE " + REPO + "/issues/271/labels/no-auto-merge", 404, "{}");

    gitHub.setLabel(271, "no-auto-merge", true, 7L);
    gitHub.setLabel(271, "no-auto-merge", false, 7L);

    assertThat(requests).containsExactly(
        "POST " + REPO + "/issues/271/labels",
        "DELETE " + REPO + "/issues/271/labels/no-auto-merge");
    assertThat(bodies.get(0)).contains("no-auto-merge");
  }

  @Test
  void failedLabelRemovalIsAnError() {
    reply("DELETE " + REPO + "/issues/271/labels/no-auto-merge", 500, "{}");

    assertThatThrownBy(() -> gitHub.setLabel(271, "no-auto-merge", false, 7L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void readsEveryPageAndTheSourceOfRenamedDependencyFiles() {
    String firstPage = java.util.stream.IntStream.range(0, 100)
        .mapToObj(index -> "{\"filename\":\"backend/A" + index + ".java\"}")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    reply("GET " + REPO + "/pulls/271/files?per_page=100&page=1", 200, firstPage);
    reply("GET " + REPO + "/pulls/271/files?per_page=100&page=2", 200, """
        [{"filename":"docs/old-build.txt","previous_filename":"backend/build.gradle.kts"}]""");

    assertThat(gitHub.changedPaths(271, 7L))
        .hasSize(102).contains("backend/build.gradle.kts", "docs/old-build.txt", "backend/A0.java");
  }

  @Test
  void fileReadFailuresCannotBeMistakenForAnOrdinaryCodeChange() {
    String path = "GET " + REPO + "/pulls/271/files?per_page=100&page=1";
    reply(path, 503, "{}");
    assertThatThrownBy(() -> gitHub.changedPaths(271, 7L)).hasMessageContaining("503");
    reply(path, 200, "{}");
    assertThatThrownBy(() -> gitHub.changedPaths(271, 7L)).hasMessageContaining("not an array");
  }

  @Test
  void cappedFileListCannotRemoveManualReview() {
    String pageOfFiles = java.util.stream.IntStream.range(0, 100)
        .mapToObj(index -> "{\"filename\":\"backend/A" + index + ".java\"}")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    for (int page = 1; page <= 30; page++) {
      reply("GET " + REPO + "/pulls/271/files?per_page=100&page=" + page, 200, pageOfFiles);
    }

    assertThatThrownBy(() -> gitHub.changedPaths(271, 7L)).hasMessageContaining("limit reached");
  }

  @Test
  void explainsDependencyReviewOnceWithoutReplacingTheDescription() {
    String path = REPO + "/pulls/271";
    reply("GET " + path, 200, "{\"body\":\"Original description\"}");
    reply("PATCH " + path, 200, "{}");

    gitHub.noteDependencyReview(271, 7L);

    String updatedBody = bodies.get(1);
    assertThat(updatedBody).contains("Original description", "Human merge required",
        "build or dependency files", "no-auto-merge");
    reply("GET " + path, 200, updatedBody);
    gitHub.noteDependencyReview(271, 7L);
    assertThat(requests).containsExactly("GET " + path, "PATCH " + path, "GET " + path);
  }

  @Test
  void readsWhetherThePullRequestMergedOrIsArmed() {
    reply("GET " + REPO + "/pulls/271", 200,
        "{\"state\":\"open\",\"merged\":false,\"auto_merge\":{\"merge_method\":\"squash\"}}");

    assertThat(gitHub.readPullRequest(271, 7L)).isEqualTo(new PullRequestState(true, false, true));
  }

  @Test
  void readsTheNewestCiRunAndTheNewestReviewForCommit() {
    reply("GET " + REPO + "/actions/runs?head_sha=sha1&per_page=50", 200, """
        {"workflow_runs":[
          {"id":10,"name":"CI","status":"completed","conclusion":"failure"},
          {"id":12,"name":"CI","status":"completed","conclusion":"success"},
          {"id":99,"name":"Publish","status":"in_progress","conclusion":null}]}""");
    reply("GET " + REPO + "/commits/sha1/check-runs?check_name=Code%20Review", 200, """
        {"check_runs":[{"id":1,"status":"completed","conclusion":"failure"},
                       {"id":2,"status":"completed","conclusion":"success"}]}""");

    assertThat(gitHub.checks("sha1", 7L))
        .isEqualTo(new ChecksReport("sha1", true, true, 12, true, true));
  }

  @Test
  void commitWithNoRunsYetIsNotComplete() {
    reply("GET " + REPO + "/actions/runs?head_sha=sha1&per_page=50", 200,
        "{\"workflow_runs\":[]}");
    reply("GET " + REPO + "/commits/sha1/check-runs?check_name=Code%20Review", 200,
        "{\"check_runs\":[{\"id\":1,\"status\":\"in_progress\",\"conclusion\":null}]}");

    ChecksReport report = gitHub.checks("sha1", 7L);
    assertThat(report.complete()).isFalse();
    assertThat(report.ciRunId()).isZero();
  }

  @Test
  void briefsFailedJobsAndSaysWhenTheLogCannotBeRead() {
    reply("GET " + REPO + "/actions/runs/12/jobs?per_page=100", 200, """
        {"jobs":[
          {"id":1,"name":"Backend Build & Test","conclusion":"failure",
           "steps":[{"name":"Run tests","conclusion":"failure"},
                    {"name":"Checkout","conclusion":"success"}]},
          {"id":2,"name":"Frontend Build & Test","conclusion":"success","steps":[]},
          {"id":3,"name":"Software Factory Build & Test","conclusion":"timed_out","steps":[]}]}""");
    reply("GET " + REPO + "/actions/jobs/1/logs", 403, "{}");
    replies.put("GET " + REPO + "/actions/jobs/3/logs",
        new Reply(302, "", Map.of("Location", "https://attacker.example/log")));

    String brief =
        gitHub.failureBrief(new ChecksReport("sha1", true, false, 12, false, false), 271, 7L);

    assertThat(brief)
        .contains("## CI job failed: Backend Build & Test")
        .contains("Failed step: Run tests")
        .doesNotContain("Checkout")
        .doesNotContain("Frontend Build & Test")
        .contains("grant the GitHub App `actions: read`")
        .contains("## CI job failed: Software Factory Build & Test")
        .contains("The job log could not be fetched: GitHub answered 302");
  }

  @Test
  void briefsTheReviewersVerdictAndItsOwnFindingsOnThatCommit() {
    reply("GET " + REPO + "/commits/sha1/check-runs?check_name=Code%20Review", 200, """
        {"check_runs":[{"id":1,"output":{"title":"Old","summary":"stale"}},
          {"id":2,"output":{"title":"Changes requested","summary":"Leaks a token"}}]}""");
    reply("GET " + REPO + "/pulls/271/comments?per_page=100", 200, """
        [{"user":{"login":"%s"},"commit_id":"sha1","path":"A.java","line":4,"body":"Token logged"},
         {"user":{"login":"%s"},"commit_id":"old","path":"B.java","line":1,"body":"Outdated"},
         {"user":{"login":"someone"},"commit_id":"sha1","path":"C.java","line":1,"body":"Nit"}]"""
            .formatted(BOT, BOT));

    String brief =
        gitHub.failureBrief(new ChecksReport("sha1", true, true, 12, true, false), 271, 7L);

    assertThat(brief)
        .contains("Changes requested").contains("Leaks a token").doesNotContain("stale")
        .contains("### A.java:4").contains("Token logged")
        .doesNotContain("Outdated").doesNotContain("Nit");
  }

  @Test
  void saysSoWhenCiNeverReported() {
    assertThat(gitHub.failureBrief(new ChecksReport("sha1", false, false, 0, false, false), 1, 7L))
        .contains("CI did not report a result for sha1");
  }

  @Test
  void parksThePullRequestWithBothMutationsAndToleratesFailure() {
    reply("POST /graphql", 200, "{\"data\":{}}");

    gitHub.park("PR_271", 7L);

    assertThat(requests).containsExactly("POST /graphql", "POST /graphql");
    assertThat(bodies.get(0)).contains("disablePullRequestAutoMerge").contains("PR_271");
    assertThat(bodies.get(1)).contains("convertPullRequestToDraft");
  }
}
