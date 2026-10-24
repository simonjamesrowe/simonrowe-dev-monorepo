package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.config.CveRepairProperties;
import com.simonrowe.factory.cvefix.dependencytrack.DependencyTrackClient;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.domain.RepairPlan;
import com.simonrowe.factory.cvefix.domain.RepairPlanner;
import com.simonrowe.factory.cvefix.osv.OsvClient;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRepository;
import io.temporal.spring.boot.ActivityImpl;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads findings and records scan outcomes; it has no repository mutation dependency.
 *
 * <p>The {@code @ConditionalOnProperty} confines the {@code cve-fix} poller to the container
 * holding {@code DEPENDENCYTRACK_API_KEY}. {@code software-factory} and {@code deployer} run the
 * same image, and {@code @ActivityImpl} alone makes Temporal's Spring Boot starter create a worker
 * for the queue, so without this both containers poll it. The deployer holds no Dependency-Track
 * credential by design; when it won the task, {@code fetchFindings} called Dependency-Track with an
 * empty key and the scan died with {@code RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED} while the
 * credential on the intended container was perfectly good. Nothing in that failure mentions
 * authentication or names the wrong container.
 *
 * <p>Note the condition is evaluated by the component scanner, so declaring this class through an
 * explicit {@code @Bean} method would register it unconditionally and silently ignore the
 * annotation — the same trap {@code DeployActivitiesImpl} documents. {@code
 * CveFixWorkerRegistrationTest} pins the behaviour by component-scanning for real.
 */
@Component
@ConditionalOnProperty(name = "factory.cvefix.enabled", havingValue = "true")
@ActivityImpl(taskQueues = CveFixTaskQueues.CVE_FIX)
public class CveFixActivitiesImpl implements CveFixActivities {

  /**
   * Statuses reached only after {@code fetchFindings} returns successfully, so a run that failed
   * before or during that call is never treated as "the previous scan" — see
   * {@link CveFixRunRepository#findFirstByIdNotAndStatusInOrderByStartedAtDesc}.
   */
  private static final EnumSet<CveFixStatus> OBSERVED_DEPENDENCY_TRACK =
      EnumSet.of(CveFixStatus.COMPLETED, CveFixStatus.NO_FINDINGS);

  private static final Logger LOGGER = LoggerFactory.getLogger(CveFixActivitiesImpl.class);

  private final DependencyTrackClient dependencyTrackClient;
  private final CveFixRunRepository runRepository;
  private final OsvClient osvClient;
  private final CveRepairProperties repairProperties;

  public CveFixActivitiesImpl(
      final DependencyTrackClient dependencyTrackClient,
      final CveFixRunRepository runRepository,
      final OsvClient osvClient,
      final CveRepairProperties repairProperties) {
    this.dependencyTrackClient = dependencyTrackClient;
    this.runRepository = runRepository;
    this.osvClient = osvClient;
    this.repairProperties = repairProperties;
  }

  @Override
  public List<RepairPlan> planRepairs(final List<ComponentFindings> components) {
    List<RepairPlan> plans = new ArrayList<>();
    for (ComponentFindings component : components) {
      if (!repairProperties.projects().contains(component.project())) {
        continue;
      }
      Optional<List<String>> pkg = RepairPlanner.osvPackage(component.purl());
      if (pkg.isEmpty()) {
        continue;
      }
      try {
        RepairPlanner.plan(
                component,
                osvClient.advisories(
                    pkg.get().get(0), pkg.get().get(1), component.componentVersion()))
            .ifPresent(plans::add);
      } catch (IllegalStateException exception) {
        LOGGER.warn("Could not ask OSV about {}; leaving it for a person", component.purl(),
            exception);
      }
    }
    return List.copyOf(plans);
  }

  @Override
  public List<ComponentFindings> fetchFindings() {
    return ComponentFindings.group(dependencyTrackClient.findings());
  }

  @Override
  public void recordRun(final CveFixRunRecord record) {
    runRepository.save(record);
  }

  @Override
  public boolean previousScanFoundFindings(final String workflowId) {
    return runRepository
        .findFirstByIdNotAndStatusInOrderByStartedAtDesc(
            CveFixRunRecord.idFor(workflowId), OBSERVED_DEPENDENCY_TRACK)
        .map(previous -> previous.findingsSeen() > 0)
        .orElse(false);
  }
}
