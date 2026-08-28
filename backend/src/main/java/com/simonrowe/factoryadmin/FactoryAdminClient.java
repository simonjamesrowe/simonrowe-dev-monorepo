package com.simonrowe.factoryadmin;

import java.util.Map;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Bounded HTTP adapter to the two unrouted factory containers.
 *
 * <p>Each factory endpoint answers in its own module's shape, so this class owns one small wire
 * record per differing response and returns a single normalised {@link FactoryRunAccepted}. The
 * alternative — deserialising every response into one wide record — silently produced null run
 * ids, because Spring Boot disables Jackson's unknown-property failure and a field that simply
 * is not there looks exactly like a field the factory chose not to send.
 */
@Component
public class FactoryAdminClient {

  private static final String TOKEN_HEADER = "X-Factory-Token";
  private static final String STATUS_PATH = "/api/factory/status";

  private final RestClient factory;
  private final RestClient deployer;
  private final String token;

  public FactoryAdminClient(final FactoryAdminProperties properties) {
    this.factory = client(properties.factoryBaseUrl(), properties);
    this.deployer = client(properties.deployerBaseUrl(), properties);
    this.token = properties.triggerToken();
  }

  private static RestClient client(
      final String baseUrl, final FactoryAdminProperties properties) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            new RestTemplateBuilder()
                .connectTimeout(properties.timeout())
                .readTimeout(properties.timeout())
                .buildRequestFactory())
        .build();
  }

  public FactoryInstanceStatus factoryStatus() {
    return factory.get().uri(STATUS_PATH).header(TOKEN_HEADER, token)
        .retrieve().body(FactoryInstanceStatus.class);
  }

  /**
   * Asks the deployer for its status without the token.
   *
   * <p>The deployer holds no {@code FACTORY_TRIGGER_TOKEN} and the status endpoint does not check
   * one, so sending it here would only widen where the credential travels.
   */
  public FactoryInstanceStatus deployerStatus() {
    return deployer.get().uri(STATUS_PATH).retrieve().body(FactoryInstanceStatus.class);
  }

  public FactoryRunAccepted startFeedback(
      final String owner, final String repository, final int pullNumber) {
    FeedbackAcceptedWire wire =
        post(
            "/api/feedback",
            Map.of(
                "owner", owner,
                "repository", repository,
                "pullNumber", pullNumber,
                "dryRun", false),
            FeedbackAcceptedWire.class);
    return new FactoryRunAccepted(
        wire.workflowId(), null, "Feedback run accepted for pull request " + pullNumber);
  }

  public FactoryRunAccepted startVulnerabilityScan() {
    RunAcceptedWire wire = post("/api/vulnerability-scans", Map.of(), RunAcceptedWire.class);
    return new FactoryRunAccepted(wire.workflowId(), wire.runId(), wire.detail());
  }

  public FactoryRunAccepted startDeploy(final String sha) {
    DeployAcceptedWire wire =
        post("/api/deploys", Map.of("sha", sha), DeployAcceptedWire.class);
    return new FactoryRunAccepted(
        wire.workflowId(), wire.runId(), "Redeploying " + shortened(wire.sha()));
  }

  public FactoryRunAccepted startPlatformBackup(final boolean dryRun) {
    RunAcceptedWire wire =
        post("/api/platform-backups", Map.of("dryRun", dryRun), RunAcceptedWire.class);
    return new FactoryRunAccepted(wire.workflowId(), wire.runId(), wire.detail());
  }

  public FactoryRunProgress progress(final String workflowId) {
    return factory
        .get()
        .uri("/api/factory/runs/{workflowId}", workflowId)
        .header(TOKEN_HEADER, token)
        .retrieve()
        .body(FactoryRunProgress.class);
  }

  private <T> T post(final String path, final Object body, final Class<T> type) {
    T wire =
        factory
            .post()
            .uri(path)
            .header(TOKEN_HEADER, token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .body(body)
            .retrieve()
            .body(type);
    if (wire == null) {
      throw new RestClientException("The Software Factory accepted the request with no body");
    }
    return wire;
  }

  private static String shortened(final String sha) {
    return sha == null || sha.length() < 7 ? String.valueOf(sha) : sha.substring(0, 7);
  }

  /** The shape shared by the vulnerability-scan and platform-backup endpoints. */
  record RunAcceptedWire(String workflowId, String runId, String detail) {
  }

  /** Feedback reports acceptance as a flag and mints no run id of its own. */
  record FeedbackAcceptedWire(String workflowId, boolean started) {
  }

  /** Deploy echoes the commit rather than a free-text detail. */
  record DeployAcceptedWire(String workflowId, String runId, String sha) {
  }
}
