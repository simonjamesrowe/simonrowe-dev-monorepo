package com.simonrowe.factoryadmin;

import java.util.Map;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

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

  /**
   * The request field three modules use to ask for a rehearsal rather than the real thing.
   *
   * <p>A constant because the factory reads this exact name on all three endpoints: a typo in one
   * of them would not fail, it would silently run for real.
   */
  private static final String DRY_RUN = "dryRun";

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

  /**
   * Starts a code review by hand.
   *
   * <p><strong>No {@code expectedHeadSha} is sent, deliberately.</strong> The webhook path builds
   * its workflow id from the head SHA under {@code REJECT_DUPLICATE}, so the same commit can never
   * be reviewed twice that way — not even after a failed review. Omitting the SHA makes
   * {@code ReviewWorkflowService} mint a UUID instead, which is the only thing that lets an
   * operator re-review a commit whose first review died. That recovery is the whole point of this
   * action, so the field must stay absent.
   *
   * @param owner the repository owner
   * @param repository the repository
   * @param pullNumber the pull request to review
   * @param publish whether to post the review; false reviews and posts nothing at all
   * @return the accepted run
   */
  public FactoryRunAccepted startCodeReview(
      final String owner, final String repository, final int pullNumber, final boolean publish) {
    ReviewAcceptedWire wire =
        post(
            "/api/reviews",
            Map.of(
                "owner", owner,
                "repository", repository,
                "pullNumber", pullNumber,
                "publish", publish),
            ReviewAcceptedWire.class);
    if (!wire.started()) {
      // Near-unreachable on this path, because the workflow id carries a fresh UUID every time.
      // Reported rather than swallowed: a 202 here would claim a review that never started.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A review with that identity is already running");
    }
    return new FactoryRunAccepted(
        wire.workflowId(),
        null,
        publish
            ? "Review accepted for pull request " + pullNumber
            : "Dry-run review accepted for pull request " + pullNumber
                + "; it will post nothing to GitHub");
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
                DRY_RUN, false),
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
        post("/api/platform-backups", Map.of(DRY_RUN, dryRun), RunAcceptedWire.class);
    return new FactoryRunAccepted(wire.workflowId(), wire.runId(), wire.detail());
  }

  public FactoryRunAccepted startLogWatchScan(final boolean dryRun) {
    RunAcceptedWire wire =
        post("/api/logwatch/scans", Map.of(DRY_RUN, dryRun), RunAcceptedWire.class);
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

  /** Code review reports acceptance as a flag, like feedback, and mints no run id of its own. */
  record ReviewAcceptedWire(String workflowId, boolean started) {
  }

  /** Feedback reports acceptance as a flag and mints no run id of its own. */
  record FeedbackAcceptedWire(String workflowId, boolean started) {
  }

  /** Deploy echoes the commit rather than a free-text detail. */
  record DeployAcceptedWire(String workflowId, String runId, String sha) {
  }
}
