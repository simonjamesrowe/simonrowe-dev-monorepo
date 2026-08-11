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
  private static final String NO_CHECKS = "{\"total_count\":0,\"check_runs\":[]}";

  private HttpServer server;
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final List<String> seenHeaderNames = new CopyOnWriteArrayList<>();
  private final List<String> seenQueries = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/repos/",
        exchange -> {
          String key = exchange.getRequestURI().getPath();
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
