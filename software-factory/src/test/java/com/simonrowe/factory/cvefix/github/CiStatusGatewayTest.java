package com.simonrowe.factory.cvefix.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.CiOutcome.CiState;
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

class CiStatusGatewayTest {

  private static final String HEAD_SHA = "abc123";
  private static final String CHECK_RUNS_PATH = "/repos/acme/widgets/commits/abc123/check-runs";
  private static final String ANNOTATIONS_PATH = "/repos/acme/widgets/check-runs/77/annotations";
  private static final String NO_CHECKS = "{\"total_count\":0,\"check_runs\":[]}";

  /**
   * The shape GitHub Actions actually returns, transcribed from a live response for this
   * repository: {@code output.summary} and {@code output.text} are both null, and the only usable
   * signal is {@code output.annotations_count} plus the annotations endpoint.
   *
   * <p><strong>{@code annotations_count} appears only inside {@code output}, never at top level,
   * and no fixture in this class may put it at top level.</strong> A check run's top-level keys are
   * exactly {@code app}, {@code check_suite}, {@code completed_at}, {@code conclusion},
   * {@code details_url}, {@code external_id}, {@code head_sha}, {@code html_url}, {@code id},
   * {@code name}, {@code node_id}, {@code output}, {@code pull_requests}, {@code started_at},
   * {@code status} and {@code url}. An earlier version of these fixtures supplied the count in
   * both places, which let {@link CiStatusGateway} read the top-level key and still pass every
   * test while fetching no annotations at all in production. Fixtures that populate the output
   * fields, or that invent a top-level count, describe a check run this repository never produces.
   */
  private static final String REAL_ACTIONS_SHAPE =
      """
      {"total_count":2,"check_runs":[
        {"id":77,"name":"Backend Build & Test","status":"completed","conclusion":"failure",
         "output":{"title":null,"summary":null,"text":null,"annotations_count":2,
                   "annotations_url":"https://api.github.com/x/check-runs/77/annotations"}},
        {"id":78,"name":"Publish Frontend Image","status":"completed","conclusion":"success",
         "output":{"title":null,"summary":null,"text":null,"annotations_count":0,
                   "annotations_url":"https://api.github.com/x/check-runs/78/annotations"}}]}
      """;

  private HttpServer server;
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final List<String> seenHeaderNames = new CopyOnWriteArrayList<>();
  private final List<String> seenQueries = new CopyOnWriteArrayList<>();
  private final List<String> seenPaths = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/repos/",
        exchange -> {
          String key = exchange.getRequestURI().getPath();
          seenPaths.add(key);
          seenHeaderNames.addAll(exchange.getRequestHeaders().keySet());
          seenQueries.add(String.valueOf(exchange.getRequestURI().getQuery()));
          byte[] body = responses.getOrDefault(key, NO_CHECKS).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private CiStatusGateway gateway(final List<String> advisoryChecks) {
    CodeReviewProperties codeReviewProperties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "http://localhost:" + server.getAddress().getPort(),
                null,
                null,
                null,
                null,
                Duration.ofSeconds(5)),
            null,
            null);
    CveFixProperties properties =
        new CveFixProperties(
            true,
            "acme",
            "widgets",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new CveFixProperties.Ci(null, 0, null, advisoryChecks));
    return new CiStatusGateway(codeReviewProperties, properties, new ObjectMapper());
  }

  private CiStatusGateway gateway() {
    return gateway(null);
  }

  @Test
  void reportsPendingWhenNoChecksHaveRegistered() {
    responses.put(CHECK_RUNS_PATH, NO_CHECKS);

    CiOutcome outcome = gateway().outcomeFor(HEAD_SHA);

    assertThat(outcome.state()).isEqualTo(CiState.PENDING);
    assertThat(outcome.failedCheckNames()).isEmpty();
    assertThat(outcome.detail()).isNotBlank();
  }

  @Test
  void reportsPendingWhileOneCheckIsStillRunning() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":2,"check_runs":[
          {"name":"build","status":"completed","conclusion":"success"},
          {"name":"test","status":"in_progress","conclusion":null}]}
        """);

    CiOutcome outcome = gateway().outcomeFor(HEAD_SHA);

    assertThat(outcome.state()).isEqualTo(CiState.PENDING);
    assertThat(outcome.failedCheckNames()).isEmpty();
  }

  @Test
  void reportsGreenWhenEveryCheckPassedWasNeutralOrSkipped() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":3,"check_runs":[
          {"name":"build","status":"completed","conclusion":"success"},
          {"name":"lint","status":"completed","conclusion":"neutral"},
          {"name":"deploy","status":"completed","conclusion":"skipped"}]}
        """);

    CiOutcome outcome = gateway().outcomeFor(HEAD_SHA);

    assertThat(outcome.state()).isEqualTo(CiState.GREEN);
    assertThat(outcome.failedCheckNames()).isEmpty();
  }

  @Test
  void reportsRedAndNamesOnlyTheFailedCheck() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":2,"check_runs":[
          {"name":"build","status":"completed","conclusion":"success"},
          {"name":"test","status":"completed","conclusion":"failure"}]}
        """);

    CiOutcome outcome = gateway().outcomeFor(HEAD_SHA);

    assertThat(outcome.state()).isEqualTo(CiState.RED);
    assertThat(outcome.failedCheckNames()).containsExactly("test");
    assertThat(outcome.detail()).contains("test");
  }

  @Test
  void treatsTimedOutAndCancelledConclusionsAsFailures() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":3,"check_runs":[
          {"name":"build","status":"completed","conclusion":"success"},
          {"name":"slow","status":"completed","conclusion":"timed_out"},
          {"name":"stopped","status":"completed","conclusion":"cancelled"}]}
        """);

    CiOutcome outcome = gateway().outcomeFor(HEAD_SHA);

    assertThat(outcome.state()).isEqualTo(CiState.RED);
    assertThat(outcome.failedCheckNames()).containsExactlyInAnyOrder("slow", "stopped");
  }

  @Test
  void ignoresAnAdvisoryCheckThatFailed() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":3,"check_runs":[
          {"name":"build","status":"completed","conclusion":"success"},
          {"name":"test","status":"completed","conclusion":"success"},
          {"name":"evaluate","status":"completed","conclusion":"failure"}]}
        """);

    CiOutcome outcome = gateway(List.of("evaluate")).outcomeFor(HEAD_SHA);

    assertThat(outcome.state()).isEqualTo(CiState.GREEN);
    assertThat(outcome.failedCheckNames()).doesNotContain("evaluate");
    assertThat(outcome.failedCheckNames()).isEmpty();
  }

  @Test
  void reportsPendingWhenTheOnlyRegisteredCheckIsAdvisory() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":1,"check_runs":[
          {"name":"evaluate","status":"completed","conclusion":"success"}]}
        """);

    CiOutcome outcome = gateway(List.of("evaluate")).outcomeFor(HEAD_SHA);

    assertThat(outcome.state()).isEqualTo(CiState.PENDING);
    assertThat(outcome.failedCheckNames()).isEmpty();
  }

  @Test
  void reportsPendingWhenGithubReturnsFewerChecksThanItCounts() {
    StringBuilder runs = new StringBuilder();
    for (int index = 0; index < 100; index++) {
      runs.append(index == 0 ? "" : ",")
          .append("{\"name\":\"check-")
          .append(index)
          .append("\",\"status\":\"completed\",\"conclusion\":\"success\"}");
    }
    responses.put(CHECK_RUNS_PATH, "{\"total_count\":150,\"check_runs\":[" + runs + "]}");

    CiOutcome outcome = gateway().outcomeFor(HEAD_SHA);

    assertThat(outcome.state()).isEqualTo(CiState.PENDING);
    assertThat(outcome.failedCheckNames()).isEmpty();
    assertThat(outcome.detail()).contains("150").contains("100");
  }

  @Test
  void asksForOneHundredChecksPerPage() {
    responses.put(CHECK_RUNS_PATH, NO_CHECKS);

    gateway().outcomeFor(HEAD_SHA);

    assertThat(seenQueries).containsExactly("per_page=100");
  }

  @Test
  void sendsNoCredentialWithTheCheckRunsRequest() {
    responses.put(CHECK_RUNS_PATH, NO_CHECKS);

    gateway().outcomeFor(HEAD_SHA);

    assertThat(seenHeaderNames).isNotEmpty();
    assertThat(seenHeaderNames).anyMatch(name -> name.equalsIgnoreCase("accept"));
    assertThat(seenHeaderNames).noneMatch(name -> name.equalsIgnoreCase("authorization"));
    assertThat(seenHeaderNames).noneMatch(name -> name.equalsIgnoreCase("proxy-authorization"));
  }

  @Test
  void collectsAnnotationsWhenActionsLeavesTheOutputFieldsNull() {
    responses.put(CHECK_RUNS_PATH, REAL_ACTIONS_SHAPE);
    responses.put(
        ANNOTATIONS_PATH,
        """
        [{"path":"backend/build.gradle.kts","start_line":42,"end_line":42,
          "annotation_level":"failure","title":"Build failed","raw_details":null,
          "message":"Could not resolve org.example:lib:2.0.0"},
         {"path":".github","start_line":1,"end_line":1,"annotation_level":"warning",
          "title":null,"message":"Process completed with exit code 1."}]
        """);

    String logs = gateway().failureLogs(HEAD_SHA);

    // The regression this covers: with only output.summary/text read, this returned a heading and
    // blank lines, so the repair agent was handed no failure context whatsoever.
    assertThat(logs).contains("### Backend Build & Test");
    assertThat(logs).contains("[failure] backend/build.gradle.kts:42 - Could not resolve");
    assertThat(logs).contains("[warning] .github:1 - Process completed with exit code 1.");
    assertThat(logs).doesNotContain("Publish Frontend Image");
    assertThat(seenPaths).contains(ANNOTATIONS_PATH);
  }

  @Test
  void requestsNoAnnotationsForChecksThatPassed() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":1,"check_runs":[
          {"id":77,"name":"Backend Build & Test","status":"completed","conclusion":"success",
           "output":{"summary":null,"text":null,"annotations_count":2}}]}
        """);

    assertThat(gateway().failureLogs(HEAD_SHA)).isEmpty();
    assertThat(seenPaths).doesNotContain(ANNOTATIONS_PATH);
  }

  @Test
  void requestsNoAnnotationsWhenTheOnlyFailedCheckIsAdvisory() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":2,"check_runs":[
          {"id":77,"name":"evaluate","status":"completed","conclusion":"failure",
           "output":{"summary":null,"text":null,"annotations_count":2}},
          {"id":78,"name":"build","status":"completed","conclusion":"success",
           "output":{"summary":null,"text":null,"annotations_count":0}}]}
        """);

    assertThat(gateway(List.of("evaluate")).failureLogs(HEAD_SHA)).isEmpty();
    assertThat(seenPaths).doesNotContain(ANNOTATIONS_PATH);
  }

  @Test
  void keepsTheFailureHeadingWhenTheAnnotationRequestFails() {
    responses.put(CHECK_RUNS_PATH, REAL_ACTIONS_SHAPE);
    statuses.put(ANNOTATIONS_PATH, 404);

    // A thin prompt beats a failed activity: the repair attempt has already been paid for by the
    // time the failure logs are read.
    String logs = gateway().failureLogs(HEAD_SHA);

    assertThat(logs).contains("### Backend Build & Test");
    assertThat(seenPaths).contains(ANNOTATIONS_PATH);
  }

  @Test
  void stopsRequestingAnnotationsAtTheRequestCap() {
    StringBuilder runs = new StringBuilder();
    for (int index = 1; index <= 8; index++) {
      runs.append(index == 1 ? "" : ",")
          .append("{\"id\":")
          .append(index)
          .append(",\"name\":\"check-")
          .append(index)
          .append("\",\"status\":\"completed\",\"conclusion\":\"failure\",")
          .append("\"output\":{\"summary\":null,\"text\":null,")
          .append("\"annotations_count\":1}}");
      responses.put(
          "/repos/acme/widgets/check-runs/" + index + "/annotations",
          "[{\"annotation_level\":\"failure\",\"message\":\"boom " + index + "\"}]");
    }
    responses.put(CHECK_RUNS_PATH, "{\"total_count\":8,\"check_runs\":[" + runs + "]}");

    String logs = gateway().failureLogs(HEAD_SHA);

    assertThat(seenPaths.stream().filter(path -> path.endsWith("/annotations")).count())
        .isEqualTo(5);
    assertThat(logs).contains("boom 1").contains("boom 5").doesNotContain("boom 6");
    // Every failed check is still named, even the ones past the annotation cap.
    assertThat(logs).contains("### check-8");
  }

  @Test
  void collectsFailureLogsForFailedNonAdvisoryChecksOnly() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":3,"check_runs":[
          {"name":"build","status":"completed","conclusion":"success",
           "output":{"summary":"all good","text":"green log"}},
          {"name":"test","status":"completed","conclusion":"failure",
           "output":{"summary":"3 tests failed","text":"expected 1 but was 2"}},
          {"name":"evaluate","status":"completed","conclusion":"failure",
           "output":{"summary":"promptfoo budget","text":"advisory log"}}]}
        """);

    String logs = gateway(List.of("evaluate")).failureLogs(HEAD_SHA);

    assertThat(logs).contains("### test");
    assertThat(logs).contains("3 tests failed");
    assertThat(logs).contains("expected 1 but was 2");
    assertThat(logs).doesNotContain("green log");
    assertThat(logs).doesNotContain("advisory log");
  }

  @Test
  void returnsEmptyFailureLogsWhenEveryCheckPassed() {
    responses.put(
        CHECK_RUNS_PATH,
        """
        {"total_count":1,"check_runs":[
          {"name":"build","status":"completed","conclusion":"success",
           "output":{"summary":"all good","text":"green log"}}]}
        """);

    assertThat(gateway().failureLogs(HEAD_SHA)).isEmpty();
  }
}
