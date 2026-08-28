package com.simonrowe.factoryadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tests the factory proxy client against a JDK {@link HttpServer} fake, following
 * {@code FactoryVersionClientTest} — {@code mockwebserver3} is not a dependency of this project
 * and the feature adds none.
 *
 * <p>The behaviour most worth pinning is the response shapes. Three factory endpoints answer in
 * three different shapes, and deserialising all of them into one wide record silently produced
 * null run ids, because Spring Boot disables Jackson's unknown-property failure so a field that
 * is absent looks identical to a field the factory chose not to send.
 */
class FactoryAdminClientTest {

  private static final String TOKEN = "trigger-token";
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String UNREACHABLE = "http://127.0.0.1:1";
  private static final String REVIEW_ACCEPTED =
      "{\"workflowId\":\"code-review-simonjamesrowe-simonrowe-dev-monorepo-130-uuid\","
          + "\"started\":true}";
  private static final String FEEDBACK_ACCEPTED =
      "{\"workflowId\":\"review-feedback-42\",\"started\":true}";

  private HttpServer factory;
  private HttpServer deployer;
  private final Map<String, String> tokensSeen = new ConcurrentHashMap<>();
  private final List<String> bodiesSeen = new ArrayList<>();

  @AfterEach
  void stopServers() {
    if (factory != null) {
      factory.stop(0);
    }
    if (deployer != null) {
      deployer.stop(0);
    }
  }

  @Test
  void readsStatusFromBothContainers() throws IOException {
    startFactory(Map.of("/api/factory/status", json(200, statusBody("software-factory"))));
    startDeployer(Map.of("/api/factory/status", json(200, statusBody("deployer"))));
    FactoryAdminClient client = client();

    assertThat(client.factoryStatus().container()).isEqualTo("software-factory");
    assertThat(client.deployerStatus().container()).isEqualTo("deployer");
  }

  @Test
  void sendsTheTokenToTheFactoryAndNotToTheDeployer() throws IOException {
    // The deployer deliberately holds no FACTORY_TRIGGER_TOKEN and its status endpoint checks
    // none, so sending the credential there would only widen where it travels.
    startFactory(Map.of("/api/factory/status", json(200, statusBody("software-factory"))));
    startDeployer(Map.of("/api/factory/status", json(200, statusBody("deployer"))));
    FactoryAdminClient client = client();

    client.factoryStatus();
    client.deployerStatus();

    assertThat(tokensSeen.get("factory:/api/factory/status")).isEqualTo(TOKEN);
    assertThat(tokensSeen).doesNotContainKey("deployer:/api/factory/status");
  }

  @Test
  void readsTheModulePrerequisitesTheFactoryReports() throws IOException {
    startFactory(Map.of("/api/factory/status", json(200, statusBody("software-factory"))));
    startDeployer(Map.of());

    FactoryInstanceStatus.ModuleStatus module = client().factoryStatus().modules().get(0);

    assertThat(module.missingPrerequisites()).containsExactly("Linear API key is not set");
    assertThat(module.ready()).isFalse();
  }

  @Test
  void neverSendsExpectedHeadShaOnManualReview() throws IOException {
    // Load-bearing, and the reason this action exists at all. The webhook builds its workflow id
    // from the head SHA under REJECT_DUPLICATE, so the same commit can never be re-reviewed that
    // way — not even after a failed review. Omitting the field makes the factory mint a UUID.
    startFactory(Map.of("/api/reviews", json(202, REVIEW_ACCEPTED)));
    startDeployer(Map.of());

    client().startCodeReview("simonjamesrowe", "simonrowe-dev-monorepo", 130, true);

    assertThat(bodiesSeen).hasSize(1);
    assertThat(bodiesSeen.get(0))
        .doesNotContain("expectedHeadSha")
        .contains("\"pullNumber\":130")
        .contains("\"publish\":true");
  }

  @Test
  void carriesTheDryRunModeOnManualReview() throws IOException {
    startFactory(Map.of("/api/reviews", json(202, REVIEW_ACCEPTED)));
    startDeployer(Map.of());

    FactoryRunAccepted accepted =
        client().startCodeReview("simonjamesrowe", "simonrowe-dev-monorepo", 130, false);

    assertThat(bodiesSeen.get(0)).contains("\"publish\":false");
    // Spelled out because a dry run posts nothing at all, including failure notices — without
    // saying so the operator has no way to know why the pull request stayed silent.
    assertThat(accepted.detail())
        .isEqualTo("Dry-run review accepted for pull request 130; it will post nothing to GitHub");
  }

  @Test
  void reportsReviewThatDidNotStartAsConflict() throws IOException {
    // ReviewController answers 202 either way, so `started` is the only signal that the workflow
    // was refused. Passing that through as an acceptance would claim a review that never ran.
    startFactory(
        Map.of("/api/reviews",
            json(202, "{\"workflowId\":\"code-review-x\",\"started\":false}")));
    startDeployer(Map.of());

    assertThatThrownBy(
        () -> client().startCodeReview("simonjamesrowe", "simonrowe-dev-monorepo", 130, true))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void normalisesTheFeedbackResponseThatCarriesNoRunId() throws IOException {
    // Feedback answers {"workflowId":..,"started":true}. Read as the common shape this produced a
    // null runId and a null detail, so the console had nothing to show but an opaque identifier.
    startFactory(Map.of("/api/feedback", json(202, FEEDBACK_ACCEPTED)));
    startDeployer(Map.of());

    FactoryRunAccepted accepted = client().startFeedback("owner", "repo", 42);

    assertThat(accepted.workflowId()).isEqualTo("review-feedback-42");
    assertThat(accepted.runId()).isNull();
    assertThat(accepted.detail()).isEqualTo("Feedback run accepted for pull request 42");
  }

  @Test
  void sendsTheConfiguredRepositoryCoordinatesWithFeedbackRequest() throws IOException {
    startFactory(Map.of("/api/feedback", json(202, FEEDBACK_ACCEPTED)));
    startDeployer(Map.of());

    client().startFeedback("simonjamesrowe", "simonrowe-dev-monorepo", 42);

    assertThat(bodiesSeen).hasSize(1);
    assertThat(bodiesSeen.get(0))
        .contains("\"owner\":\"simonjamesrowe\"")
        .contains("\"repository\":\"simonrowe-dev-monorepo\"")
        .contains("\"pullNumber\":42")
        .contains("\"dryRun\":false");
  }

  @Test
  void normalisesTheDeployResponseThatEchoesTheCommit() throws IOException {
    startFactory(
        Map.of(
            "/api/deploys",
            json(202, "{\"workflowId\":\"deploy-prod\",\"runId\":\"run-9\","
                + "\"sha\":\"" + SHA + "\"}")));
    startDeployer(Map.of());

    FactoryRunAccepted accepted = client().startDeploy(SHA);

    assertThat(accepted.workflowId()).isEqualTo("deploy-prod");
    assertThat(accepted.runId()).isEqualTo("run-9");
    assertThat(accepted.detail()).isEqualTo("Redeploying 0123456");
  }

  @Test
  void passesThroughTheScanAndBackupShapeUnchanged() throws IOException {
    startFactory(
        Map.of(
            "/api/vulnerability-scans",
            json(202, "{\"workflowId\":\"cve-scan-manual-1\",\"runId\":\"run-1\","
                + "\"detail\":\"Vulnerability scan accepted\"}"),
            "/api/platform-backups",
            json(202, "{\"workflowId\":\"platform-backup-manual\",\"runId\":\"run-2\","
                + "\"detail\":\"Platform backup dry run accepted\"}")));
    startDeployer(Map.of());
    FactoryAdminClient client = client();

    assertThat(client.startVulnerabilityScan().detail())
        .isEqualTo("Vulnerability scan accepted");
    assertThat(client.startPlatformBackup(true).detail())
        .isEqualTo("Platform backup dry run accepted");
  }

  @Test
  void readsRunProgress() throws IOException {
    startFactory(
        Map.of(
            "/api/factory/runs/cve-scan-manual-1",
            json(200, "{\"workflowId\":\"cve-scan-manual-1\",\"runId\":\"run-1\","
                + "\"executionStatus\":\"WORKFLOW_EXECUTION_STATUS_RUNNING\","
                + "\"phase\":\"FILING\",\"detail\":\"Filing\",\"terminal\":false}")));
    startDeployer(Map.of());

    FactoryRunProgress progress = client().progress("cve-scan-manual-1");

    assertThat(progress.phase()).isEqualTo("FILING");
    assertThat(progress.terminal()).isFalse();
  }

  @Test
  void raisesTheDownstreamStatusSoTheServiceCanTranslateIt() throws IOException {
    // A conflict, a disabled module and an unreachable container each need a different response,
    // so the client must not flatten them here.
    startFactory(Map.of("/api/platform-backups", json(409, "{\"message\":\"already running\"}")));
    startDeployer(Map.of());

    assertThatThrownBy(() -> client().startPlatformBackup(false))
        .isInstanceOf(HttpClientErrorException.Conflict.class);
  }

  @Test
  void rejectsAnAcceptanceWithNoBody() throws IOException {
    startFactory(Map.of("/api/vulnerability-scans", empty(202)));
    startDeployer(Map.of());

    assertThatThrownBy(() -> client().startVulnerabilityScan())
        .isInstanceOf(RestClientException.class);
  }

  @Test
  void failsFastWhenTheFactoryIsNotListening() {
    FactoryAdminClient client =
        new FactoryAdminClient(
            new FactoryAdminProperties(
                UNREACHABLE, UNREACHABLE, TOKEN, Duration.ofMillis(200), null, null));

    assertThatThrownBy(client::factoryStatus).isInstanceOf(RestClientException.class);
  }

  private FactoryAdminClient client() {
    return new FactoryAdminClient(
        new FactoryAdminProperties(
            baseUrl(factory), baseUrl(deployer), TOKEN, Duration.ofSeconds(2), null, null));
  }

  private void startFactory(final Map<String, Response> routes) throws IOException {
    factory = start(routes, "factory");
  }

  private void startDeployer(final Map<String, Response> routes) throws IOException {
    deployer = start(routes, "deployer");
  }

  private HttpServer start(final Map<String, Response> routes, final String label)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    routes.forEach((path, response) ->
        server.createContext(path, exchange -> {
          record(exchange, label, path);
          respond(exchange, response);
        }));
    server.start();
    return server;
  }

  private void record(final HttpExchange exchange, final String label, final String path)
      throws IOException {
    String token = exchange.getRequestHeaders().getFirst("X-Factory-Token");
    if (token != null) {
      tokensSeen.put(label + ":" + path, token);
    }
    if ("POST".equals(exchange.getRequestMethod())) {
      synchronized (bodiesSeen) {
        bodiesSeen.add(
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  private static void respond(final HttpExchange exchange, final Response response)
      throws IOException {
    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 0) {
      exchange.getResponseHeaders().add("Content-Type", "application/json");
    }
    exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream body = exchange.getResponseBody()) {
        body.write(bytes);
      }
    } else {
      exchange.close();
    }
  }

  private static String baseUrl(final HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static String statusBody(final String container) {
    return "{\"container\":\"" + container + "\",\"fetchedAt\":\"2026-08-28T09:00:00Z\","
        + "\"modules\":[{\"key\":\"linear\",\"displayName\":\"Linear filing\","
        + "\"configured\":true,\"taskQueue\":\"linear\",\"workflowPollers\":0,"
        + "\"activityPollers\":1,\"trigger\":\"upstream workflow\",\"schedule\":null,"
        + "\"missingPrerequisites\":[\"Linear API key is not set\"],\"ready\":false,"
        + "\"diagnostic\":\"Enabled but not usable: Linear API key is not set\"}]}";
  }

  private static Response json(final int status, final String body) {
    return new Response(status, body);
  }

  private static Response empty(final int status) {
    return new Response(status, "");
  }

  private record Response(int status, String body) {
  }
}
