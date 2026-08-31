package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.simonrowe.factory.logwatch.config.LogWatchTaskQueues;
import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import com.simonrowe.factory.logwatch.health.SourceHealthChecker;
import com.simonrowe.factory.logwatch.loki.AlloyHealthClient;
import com.simonrowe.factory.logwatch.loki.LokiClient;
import com.simonrowe.factory.logwatch.loki.LokiException;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRecord;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRepository;
import com.simonrowe.factory.logwatch.signature.SignatureExtractor;
import io.temporal.spring.boot.ActivityImpl;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads Loki, groups what it finds, and records scan outcomes.
 *
 * <p>The {@code @ConditionalOnProperty} confines the {@code logwatch} poller — and with it the
 * Grafana credential — to {@code software-factory}. {@code software-factory} and {@code deployer}
 * run the same image, and {@code @ActivityImpl} alone makes Temporal's Spring Boot starter create
 * a worker for the queue, so without this both containers poll it and the container holding
 * {@code /var/run/docker.sock} would hold a Loki credential it has no use for.
 *
 * <p>Note the condition is evaluated by the component scanner, so declaring this class through an
 * explicit {@code @Bean} method would register it unconditionally and silently ignore the
 * annotation — the same trap {@code DeployActivitiesImpl} and {@code CveFixActivitiesImpl}
 * document. {@code LogWatchWorkerRegistrationTest} pins the behaviour by component-scanning for
 * real, and {@code DeployerGrafanaCredentialTest} guards the compose side, because the Java gate
 * alone does not stop a future compose edit handing the credential to the deployer directly.
 */
@Component
@ConditionalOnProperty(name = "factory.logwatch.enabled", havingValue = "true")
@ActivityImpl(taskQueues = LogWatchTaskQueues.LOG_WATCH)
public class LogWatchActivitiesImpl implements LogWatchActivities {

  private final LokiClient lokiClient;
  private final AlloyHealthClient alloyHealthClient;
  private final LogWatchRunRepository runRepository;
  private final LogWatchProperties properties;

  /**
   * Creates the activities.
   *
   * @param lokiClient reads log lines and container coverage
   * @param alloyHealthClient reports whether Alloy's write path is healthy
   * @param runRepository persistence for scan records
   * @param properties the module's configuration
   */
  public LogWatchActivitiesImpl(
      final LokiClient lokiClient,
      final AlloyHealthClient alloyHealthClient,
      final LogWatchRunRepository runRepository,
      final LogWatchProperties properties) {
    this.lokiClient = lokiClient;
    this.alloyHealthClient = alloyHealthClient;
    this.runRepository = runRepository;
    this.properties = properties;
  }

  @Override
  public ScanObservation observe(final Instant from, final Instant to) {
    AlloyHealthClient.WriteHealth writeHealth = alloyHealthClient.writeHealth();

    List<LogLine> lines;
    int containers;
    try {
      lines = lokiClient.linesIn(from, to, properties.lineBudget());
      containers = lokiClient.distinctContainers(from, to);
    } catch (LokiException exception) {
      // A failed query is a source-health verdict, not a crashed scan: the run must still record
      // that it could not see, rather than dying and leaving nothing to read.
      return new ScanObservation(
          SourceHealthChecker.check(
              writeHealth.error(),
              writeHealth.reachable(),
              true,
              0,
              properties.minimumContainers(),
              Duration.between(from, to)),
          List.of(),
          0,
          false,
          0,
          0);
    }

    SourceHealth health =
        SourceHealthChecker.check(
            writeHealth.error(),
            writeHealth.reachable(),
            false,
            containers,
            properties.minimumContainers(),
            Duration.between(from, to));

    // The budget being exactly met is indistinguishable from it being exceeded, so both count as
    // truncated. Over-reporting a complete read as truncated is harmless; the reverse presents a
    // partial scan as a full one, which is what FR-006 forbids.
    boolean truncated = lines.size() >= properties.lineBudget();

    List<LogSignature> grouped =
        SignatureExtractor.group(lines).stream()
            .filter(signature -> signature.occurrences() >= properties.minimumOccurrences())
            .sorted(LogSignature.MOST_SEVERE_FIRST)
            .toList();

    // `grouped` is already in MOST_SEVERE_FIRST order, so limit() keeps the worst and drops the
    // tail - which is the whole contract of the cap (FR-005).
    int dropped = Math.max(0, grouped.size() - properties.maxPerRun());
    List<LogSignature> capped = grouped.stream().limit(properties.maxPerRun()).toList();

    return new ScanObservation(health, capped, lines.size(), truncated, containers, dropped);
  }

  @Override
  public void recordRun(final LogWatchRunRecord record) {
    runRepository.save(record);
  }
}
