package com.simonrowe.factoryadmin;

import com.simonrowe.platform.RunningVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;

/** Aggregates read-only state and applies server-side policy before proxying actions. */
@Service
public class FactoryAdminService {

  /**
   * The module keys, which are the factory's wire contract rather than local names. Named
   * constants because this class matches modules <em>by this string</em>: a typo would not fail,
   * it would silently report that module as unavailable. Mirrored from
   * {@code ModulePrerequisites.KEYS} rather than shared: {@code software-factory} is a separate
   * Gradle module and neither depends on the other, deliberately. What keeps the two sides in
   * step is {@code FactoryAdminClientTest}, which asserts this class's reading of a real factory
   * status payload.
   */
  private static final String CODE_REVIEW = "codereview";
  private static final String FEEDBACK = "feedback";
  private static final String CVEFIX = "cvefix";
  private static final String DEPLOY = "deploy";
  private static final String LINEAR = "linear";
  private static final String PLATFORM_BACKUP = "platformbackup";
  private static final String LOGWATCH = "logwatch";

  private static final List<String> ORDER =
      List.of(CODE_REVIEW, FEEDBACK, CVEFIX, DEPLOY, LINEAR, PLATFORM_BACKUP, LOGWATCH);

  /** Modules the deployer, not the factory, is the authority on. */
  private static final List<String> DEPLOYER_OWNED = List.of(DEPLOY, PLATFORM_BACKUP);

  private static final String UNKNOWN_COMMIT = "unknown";
  private static final int SHORT_COMMIT = 7;

  private final FactoryAdminClient client;
  private final FactoryAdminProperties properties;
  private final RunningVersion runningVersion;

  public FactoryAdminService(
      final FactoryAdminClient client,
      final FactoryAdminProperties properties,
      final RunningVersion runningVersion) {
    this.client = client;
    this.properties = properties;
    this.runningVersion = runningVersion;
  }

  /**
   * Reports every module, taking each one from the container that actually owns it.
   *
   * <p>One unreachable container must not blank the page: an unreachable factory still leaves the
   * deployer's two modules truthful, and vice versa, so each side is fetched independently and a
   * missing module is reported as unavailable rather than omitted.
   *
   * @return the aggregate the console renders
   */
  public FactoryAdminStatus status() {
    FactoryInstanceStatus factory = safely(client::factoryStatus);
    FactoryInstanceStatus deployer = safely(client::deployerStatus);
    Map<String, FactoryInstanceStatus.ModuleStatus> modules = new LinkedHashMap<>();
    if (factory != null) {
      factory.modules().forEach(module -> modules.put(module.key(), module));
    }
    if (deployer != null) {
      deployer.modules().stream()
          .filter(module -> DEPLOYER_OWNED.contains(module.key()))
          .forEach(module -> modules.put(module.key(), module));
    }
    if (deployer == null) {
      DEPLOYER_OWNED.forEach(key -> modules.computeIfPresent(key,
          (ignored, module) -> withoutOwnerConfirmation(module)));
    }
    List<FactoryInstanceStatus.ModuleStatus> ordered = new ArrayList<>();
    for (String key : ORDER) {
      FactoryInstanceStatus.ModuleStatus module = modules.get(key);
      ordered.add(module == null ? unavailable(key) : module);
    }
    return new FactoryAdminStatus(
        Instant.now(),
        runningVersion.commit(),
        properties.owner() + "/" + properties.repository(),
        factory != null,
        deployer != null,
        ordered);
  }

  /**
   * Keeps what Temporal knows when the owning container cannot be asked.
   *
   * <p>Starting any of these workflows needs only a Temporal client, so the question that decides
   * whether a module can actually do work is not "is the deployer's HTTP endpoint up" — it is
   * "does anything poll that queue for activities". Temporal answers that globally, and the
   * reachable {@code software-factory} can read it for the deployer's queues just as well as the
   * deployer can. Discarding those counts because an HTTP probe failed threw away the more
   * reliable fact and reported a module as flatly unavailable when the page could say something
   * true and specific instead.
   *
   * <p>What genuinely is unknown is the deployer's own enable flag, so {@code configured} becomes
   * null rather than borrowing {@code software-factory}'s value for a module it does not own —
   * that would render a confident "Off" that is about the wrong container.
   */
  private static FactoryInstanceStatus.ModuleStatus withoutOwnerConfirmation(
      final FactoryInstanceStatus.ModuleStatus module) {
    boolean pollersPresent = module.activityPollers() != null && module.activityPollers() > 0;
    return new FactoryInstanceStatus.ModuleStatus(
        module.key(),
        module.displayName(),
        null,
        module.taskQueue(),
        module.workflowPollers(),
        module.activityPollers(),
        module.trigger(),
        module.schedule(),
        module.missingPrerequisites(),
        pollersPresent,
        pollersPresent
            ? "The deployer is unreachable, so its configuration is unconfirmed, but Temporal "
                + "shows a live worker on this queue"
            : "The deployer is unreachable and nothing is polling this queue");
  }

  private static FactoryInstanceStatus.ModuleStatus unavailable(final String key) {
    String displayName = switch (key) {
      case CODE_REVIEW -> "Code review";
      case FEEDBACK -> "Feedback";
      case CVEFIX -> "Vulnerability scan";
      case DEPLOY -> "Deploy";
      case LINEAR -> "Issue tracking";
      case PLATFORM_BACKUP -> "Platform backup";
      case LOGWATCH -> "Log watch";
      default -> key;
    };
    return new FactoryInstanceStatus.ModuleStatus(
        key, displayName, null, "unavailable", null, null, "Unavailable", null, List.of(), false,
        "Owning factory service is unreachable");
  }

  /**
   * Builds the graph the console draws, from both containers.
   *
   * <p>Deploy and platform backup are taken from the deployer and reported {@code UNAVAILABLE}
   * when it cannot be reached, never from software-factory's own switched-off view of them — the
   * same split {@link #status()} makes, via the same {@link #DEPLOYER_OWNED} list. Unlike {@link
   * #status()}, a factory that cannot be reached at all fails the whole call rather than returning
   * a partial graph: every node's health depends on factory-reported state, so a half-drawn graph
   * would be actively misleading rather than merely incomplete.
   *
   * @return the merged graph
   */
  public FactoryFlow flow() {
    FactoryFlow factory = client.factoryFlow();
    FactoryFlow deployer = safelyFlow(client::deployerFlow);
    List<FactoryFlow.Node> merged = new ArrayList<>();
    for (FactoryFlow.Node node : factory.nodes()) {
      if (!DEPLOYER_OWNED.contains(node.key())) {
        merged.add(node);
      } else if (deployer == null) {
        merged.add(new FactoryFlow.Node(
            node.key(), node.kind(), node.band(), node.label(), null,
            "UNAVAILABLE", "The deployer could not be reached"));
      } else {
        merged.add(fromDeployer(deployer, node));
      }
    }
    return new FactoryFlow(factory.fetchedAt(), merged, factory.edges());
  }

  /**
   * Looks a deployer-owned node up by key in the deployer's own response, falling back to the
   * unavailable form when the deployer's graph did not report it.
   */
  private static FactoryFlow.Node fromDeployer(
      final FactoryFlow deployer, final FactoryFlow.Node factoryNode) {
    return deployer.nodes().stream()
        .filter(candidate -> candidate.key().equals(factoryNode.key()))
        .findFirst()
        .orElse(new FactoryFlow.Node(
            factoryNode.key(), factoryNode.kind(), factoryNode.band(), factoryNode.label(), null,
            "UNAVAILABLE", "The deployer did not report this node"));
  }

  private static FactoryFlow safelyFlow(final FlowSupplier supplier) {
    try {
      return supplier.get();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  /**
   * Looks up one node's recent work, for its drawer.
   *
   * <p>{@code deploy} and {@code platformbackup} are deployer-owned, exactly as in {@link
   * #flow()}, and — unlike an earlier version of this method — the deployer genuinely is asked
   * for them: it holds a separate, read-only {@code FACTORY_READ_TOKEN} that authorises only
   * {@code GET /api/factory/flow/{nodeKey}}, never the trigger token that would let it start a
   * deploy of itself. Routing these two keys to the deployer (rather than reporting them empty
   * unconditionally) is what keeps this panel consistent with the counts panel in the same
   * drawer, which already reaches the deployer successfully via the unauthenticated {@link
   * #flow()}.
   *
   * <p>Every failure — deployer unreachable, read token unconfigured there, the factory call
   * failing, anything else — still collapses to a result rather than an exception (a drawer that
   * throws takes the whole page down for a detail panel), but it collapses to {@link
   * FactoryFlowDetail#unavailable(String)}, not {@link FactoryFlowDetail#empty(String)}: those two
   * mean different things to the operator, and flattening a failed read into "no runs in the last
   * 30 days" is exactly the ambiguity this method exists to avoid, on the same terms as {@code
   * FlowDetail}'s null-items convention in software-factory. A response that itself carries null
   * {@code items} (software-factory's own artifact reader failing) is returned as-is rather than
   * defaulted to an empty list here — only this method's own catch block mints {@code
   * unavailable}.
   *
   * @param nodeKey the node whose drawer is open
   * @return that node's items, newest first; {@link FactoryFlowDetail#empty(String) empty} when
   *     the read succeeded and found nothing; {@link FactoryFlowDetail#unavailable(String)
   *     unavailable} when the source could not be read at all
   */
  public FactoryFlowDetail flowDetail(final String nodeKey) {
    try {
      return DEPLOYER_OWNED.contains(nodeKey)
          ? client.deployerFlowDetail(nodeKey)
          : client.factoryFlowDetail(nodeKey);
    } catch (RuntimeException exception) {
      return FactoryFlowDetail.unavailable(nodeKey);
    }
  }

  /**
   * Reviews a pull request on demand.
   *
   * <p>The only module whose automatic trigger is a webhook the factory cannot replay: a review
   * that failed, or one whose webhook never arrived because ingress was down, cannot be re-driven
   * from GitHub at all. This is the recovery path for both.
   *
   * @param pullNumber the pull request to review
   * @param publish whether to post the review, or review and post nothing
   * @return the accepted run
   */
  public FactoryRunAccepted startCodeReview(final int pullNumber, final boolean publish) {
    if (pullNumber < 1) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "pullNumber must be positive");
    }
    requireReady(CODE_REVIEW);
    return proxy(
        () -> client.startCodeReview(
            properties.owner(), properties.repository(), pullNumber, publish));
  }

  public FactoryRunAccepted startFeedback(final int pullNumber) {
    if (pullNumber < 1) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "pullNumber must be positive");
    }
    requireReady(FEEDBACK);
    return proxy(
        () -> client.startFeedback(properties.owner(), properties.repository(), pullNumber));
  }

  public FactoryRunAccepted startVulnerabilityScan() {
    requireReady(CVEFIX);
    return proxy(client::startVulnerabilityScan);
  }

  public FactoryRunAccepted startPlatformBackup(final boolean dryRun) {
    requireReady(PLATFORM_BACKUP);
    return proxy(() -> client.startPlatformBackup(dryRun));
  }

  /**
   * Scans production logs on demand.
   *
   * <p>A dry run reads and groups exactly as a real one does, and files nothing at all — so the
   * console's run progress is the only place its outcome appears. That is what makes it the right
   * way to check the signature rules and the occurrence thresholds against real log volume before
   * letting the module file for the first time.
   *
   * @param dryRun whether to report what would be filed without creating anything in Linear
   * @return the accepted scan
   */
  public FactoryRunAccepted startLogWatchScan(final boolean dryRun) {
    requireReady(LOGWATCH);
    return proxy(() -> client.startLogWatchScan(dryRun));
  }

  /**
   * Starts the one deploy this release permits: the commit already running.
   *
   * <p>Every check here is repeated from the browser deliberately. The console disables the
   * control, but the control is not the boundary — a request that skipped it must fail for the
   * same reasons, and the commit that gets deployed is the backend's own, never the one the
   * browser sent. The browser's value is used solely to prove the two agree.
   *
   * @param frontendCommit the commit the loaded bundle reports
   * @param confirmation the typed phrase
   * @return the accepted deploy
   */
  public FactoryRunAccepted startDeploy(
      final String frontendCommit, final String confirmation) {
    String backendCommit = runningVersion.commit();
    if (UNKNOWN_COMMIT.equals(backendCommit)
        || frontendCommit == null
        || !backendCommit.equals(frontendCommit)) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
          "Frontend and backend are not reporting the same production commit");
    }
    String shortCommit =
        backendCommit.substring(0, Math.min(SHORT_COMMIT, backendCommit.length()));
    if (!("REDEPLOY " + shortCommit).equals(confirmation)) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
          "Confirmation phrase does not match the running commit");
    }
    requireReady(DEPLOY);
    return proxy(() -> client.startDeploy(backendCommit));
  }

  /**
   * Reports a run's progress.
   *
   * @param workflowId the identity returned when the run was accepted
   * @return the run's normalised progress
   */
  public FactoryRunProgress progress(final String workflowId) {
    if (workflowId == null || !workflowId.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Malformed workflow id");
    }
    try {
      FactoryRunProgress progress = client.progress(workflowId);
      if (progress == null) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, factoryUnavailable());
      }
      return progress;
    } catch (HttpStatusCodeException exception) {
      if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such factory run");
      }
      throw translate(exception);
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, factoryUnavailable());
    }
  }

  /**
   * Refuses an action the owning module cannot currently perform.
   *
   * <p>Without this the console's disabled buttons would be the only guard, and a run started
   * against a queue with no activity poller does not fail — it sits in Temporal until a timeout,
   * looking accepted for as long as an operator is likely to keep watching.
   */
  private void requireReady(final String key) {
    FactoryInstanceStatus.ModuleStatus module = status().modules().stream()
        .filter(candidate -> key.equals(candidate.key()))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, factoryUnavailable()));
    if (!module.ready()) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
          module.diagnostic() == null
              ? module.displayName() + " is not ready to run"
              : module.displayName() + " is not ready to run: " + module.diagnostic());
    }
  }

  private static FactoryInstanceStatus safely(final StatusSupplier supplier) {
    try {
      return supplier.get();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private FactoryRunAccepted proxy(final RunSupplier supplier) {
    try {
      return supplier.get();
    } catch (HttpStatusCodeException exception) {
      throw translate(exception);
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, factoryUnavailable());
    }
  }

  /**
   * Turns a downstream status into a safe one with the same meaning.
   *
   * <p>Collapsing all of these into "unavailable" was actively misleading: a second click on a
   * running backup, a module switched off in deployment configuration, and a factory that is
   * genuinely down each need a different response from the operator. The downstream response body
   * is never forwarded — it is written by a service holding credentials, so only these fixed
   * messages cross the boundary.
   */
  private ResponseStatusException translate(final HttpStatusCodeException exception) {
    HttpStatusCode code = exception.getStatusCode();
    if (code.value() == HttpStatus.CONFLICT.value()) {
      return new ResponseStatusException(HttpStatus.CONFLICT,
          "That run is already in progress, so nothing new was started");
    }
    if (code.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
      return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "The Software Factory reports that module as disabled");
    }
    if (code.value() == HttpStatus.UNAUTHORIZED.value()
        || code.value() == HttpStatus.FORBIDDEN.value()) {
      return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "This server is not authorised to call the Software Factory");
    }
    if (code.is4xxClientError()) {
      return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "The Software Factory rejected the request");
    }
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, factoryUnavailable());
  }

  private static String factoryUnavailable() {
    return "Software Factory is unavailable";
  }

  @FunctionalInterface
  private interface StatusSupplier {
    FactoryInstanceStatus get();
  }

  @FunctionalInterface
  private interface FlowSupplier {
    FactoryFlow get();
  }

  @FunctionalInterface
  private interface RunSupplier {
    FactoryRunAccepted get();
  }
}
