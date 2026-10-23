package com.simonrowe.factory.staticanalysis.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.domain.QualityGateCondition;
import com.simonrowe.factory.staticanalysis.domain.SonarIssue;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Drives the client against a local stub shaped like SonarCloud's and GitHub's real answers. */
class SonarCloudClientTest {

  private static final String PROJECT = "simonjamesrowe_simonrowe-dev-monorepo";

  private HttpServer server;
  /** Keyed on path, or on path plus {@code ?p=N} for a paged issues response. */
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final List<String> queries = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String query = String.valueOf(exchange.getRequestURI().getQuery());
          queries.add(path + "?" + query);
          authorizations.add(
              String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          String page = query.contains("&p=2") ? "?p=2" : "";
          String body = responses.getOrDefault(path + page, responses.getOrDefault(path, "{}"));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private StaticAnalysisProperties properties(final String token) {
    String base = "http://localhost:" + server.getAddress().getPort();
    return new StaticAnalysisProperties(
        true, base, token, null, null, null, base, null, null, 0, null, null, null, null,
        Duration.ofSeconds(5));
  }

  private SonarCloudClient client() {
    return new SonarCloudClient(properties(""), new ObjectMapper());
  }

  @Test
  void readsTheLatestAnalysisWithSonarCloudsColonlessOffset() {
    responses.put("/api/project_analyses/search", """
        {"analyses":[{"key":"k","date":"2026-10-20T14:18:11+0000",
          "revision":"5c893af3abf482667b0c4c4fdf06d24b92eaac71"}]}""");

    SonarCloudClient.Analysis analysis = client().latestAnalysis().orElseThrow();

    assertThat(analysis.revision()).isEqualTo("5c893af3abf482667b0c4c4fdf06d24b92eaac71");
    assertThat(analysis.date()).isEqualTo(Instant.parse("2026-10-20T14:18:11Z"));
    assertThat(queries.getFirst()).contains("project=" + PROJECT, "branch=main");
  }

  @Test
  void reportsNoAnalysisAsEmpty() {
    responses.put("/api/project_analyses/search", "{\"analyses\":[]}");

    assertThat(client().latestAnalysis()).isEmpty();
  }

  @Test
  void readsNewCodeIssuesAndStripsTheProjectPrefixFromPaths() {
    responses.put("/api/issues/search", """
        {"paging":{"pageIndex":1,"pageSize":500,"total":1},"issues":[
          {"key":"AaFQ","rule":"typescript:S6845","severity":"MAJOR","type":"CODE_SMELL",
           "component":"%s:frontend/src/components/playlist/ReadAlong.tsx","line":252,
           "message":"`tabIndex` should only be declared on interactive elements."}]}"""
        .formatted(PROJECT));

    SonarCloudClient.IssueRead read = client().newCodeIssues();

    assertThat(read.truncated()).isFalse();
    assertThat(read.issues()).containsExactly(new SonarIssue("AaFQ", "typescript:S6845", "MAJOR",
        "CODE_SMELL", "frontend/src/components/playlist/ReadAlong.tsx", 252,
        "`tabIndex` should only be declared on interactive elements."));
    assertThat(queries.getFirst())
        .contains("componentKeys=" + PROJECT, "resolved=false", "inNewCodePeriod=true");
  }

  @Test
  void followsPagesUntilEveryIssueIsRead() {
    responses.put("/api/issues/search", issuesPage(2, "one"));
    responses.put("/api/issues/search?p=2", issuesPage(2, "two"));

    SonarCloudClient.IssueRead read = client().newCodeIssues();

    assertThat(read.issues()).extracting(SonarIssue::key).containsExactly("one", "two");
    assertThat(read.truncated()).isFalse();
  }

  @Test
  @DisplayName("a read that stops short of the total says so, so the sweep can refuse to run")
  void reportsTruncatedRead() {
    responses.put("/api/issues/search", issuesPage(3, "only"));
    responses.put("/api/issues/search?p=2", "{\"paging\":{\"total\":3},\"issues\":[]}");

    SonarCloudClient.IssueRead read = client().newCodeIssues();

    assertThat(read.total()).isEqualTo(3);
    assertThat(read.truncated()).isTrue();
  }

  @Test
  void keepsOnlyFailingQualityGateConditions() {
    responses.put("/api/qualitygates/project_status", """
        {"projectStatus":{"status":"ERROR","conditions":[
          {"status":"OK","metricKey":"new_duplicated_lines_density","comparator":"GT",
           "errorThreshold":"3","actualValue":"0.4"},
          {"status":"ERROR","metricKey":"new_coverage","comparator":"LT",
           "errorThreshold":"80","actualValue":"71.2"}]}}""");

    assertThat(client().failedConditions())
        .containsExactly(new QualityGateCondition("new_coverage", "LT", "80", "71.2"));
  }

  @Test
  void looksUpRuleNamesInOneRequest() {
    responses.put("/api/rules/search", """
        {"rules":[{"key":"java:S1192","name":"String literals should not be duplicated"}]}""");

    assertThat(client().ruleNames(List.of("java:S1192", "typescript:S6845")))
        .containsExactly(Map.entry("java:S1192", "String literals should not be duplicated"));
    assertThat(queries).hasSize(1);
    assertThat(queries.getFirst()).contains("organization=simonjamesrowe", "rule_keys=");
  }

  @Test
  @DisplayName("a failed rule lookup costs a friendly title, never the scan")
  void ruleNamesAreBestEffort() {
    statuses.put("/api/rules/search", 500);

    assertThat(client().ruleNames(List.of("java:S1192"))).isEmpty();
  }

  @Test
  void failsLoudlyWhenIssuesCannotBeRead() {
    statuses.put("/api/issues/search", 503);

    assertThatThrownBy(() -> client().newCodeIssues())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/api/issues/search")
        .hasMessageContaining("503");
  }

  @Test
  void sendsNoCredentialUnlessOneIsConfigured() {
    responses.put("/api/project_analyses/search", "{\"analyses\":[]}");

    client().latestAnalysis();
    new SonarCloudClient(properties("sonar-token"), new ObjectMapper()).latestAnalysis();

    assertThat(authorizations).containsExactly("null", "Bearer sonar-token");
  }

  @Test
  void readsTheBranchHeadFromGitHub() {
    responses.put("/repos/simonjamesrowe/simonrowe-dev-monorepo/commits/main", """
        {"sha":"f9b7d532","commit":{"committer":{"date":"2026-10-21T04:33:24Z"}}}""");

    BranchHeadClient.Head head =
        new BranchHeadClient(properties(""), new ObjectMapper()).head();

    assertThat(head).isEqualTo(
        new BranchHeadClient.Head("f9b7d532", Instant.parse("2026-10-21T04:33:24Z")));
  }

  @Test
  void branchHeadWithoutCommitIsAnError() {
    responses.put("/repos/simonjamesrowe/simonrowe-dev-monorepo/commits/main", "{}");

    assertThatThrownBy(() -> new BranchHeadClient(properties(""), new ObjectMapper()).head())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no commit");
  }

  private static String issuesPage(final int total, final String key) {
    return """
        {"paging":{"total":%d},"issues":[{"key":"%s","rule":"r","severity":"MAJOR",
          "type":"CODE_SMELL","component":"%s:backend/A.java","line":1,"message":"m"}]}"""
        .formatted(total, key, PROJECT);
  }
}
