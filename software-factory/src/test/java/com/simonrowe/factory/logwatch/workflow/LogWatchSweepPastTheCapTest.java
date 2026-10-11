package com.simonrowe.factory.logwatch.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import com.simonrowe.factory.linear.service.IssueResolver;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.simonrowe.factory.logwatch.config.LogWatchTaskQueues;
import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.LogWatchRequest;
import com.simonrowe.factory.logwatch.domain.LogWatchResult;
import com.simonrowe.factory.logwatch.domain.Severity;
import com.simonrowe.factory.logwatch.domain.Trigger;
import com.simonrowe.factory.logwatch.loki.AlloyHealthClient;
import com.simonrowe.factory.logwatch.loki.LokiClient;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRecord;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRepository;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cap fix end to end: real grouping and capping in {@link LogWatchActivitiesImpl}, the real
 * workflow, and the real {@link IssueResolver} deciding what to close.
 *
 * <p>Nothing here writes a fingerprint by hand. The tickets are the ones an uncapped scan of the
 * same lines actually files — their ids are {@link Fingerprint#of} over the key parts in each
 * {@link IssueFiling}, which is how {@code IssueFiler} keys its records — so the scan that follows
 * can only protect them if what it names as dropped hashes to exactly what filing hashed.
 */
class LogWatchSweepPastTheCapTest {

  private static final Duration QUIET_FOR = Duration.ofDays(7);

  private final LokiClient loki = mock(LokiClient.class);
  private final AlloyHealthClient alloy = mock(AlloyHealthClient.class);
  private final LinearGateway gateway = mock(LinearGateway.class);
  private final LinearIssueRepository repository = mock(LinearIssueRepository.class);

  /** The sink's records, by fingerprint: an in-memory stand-in for the audit collection. */
  private final Map<String, LinearIssueRecord> records = new LinkedHashMap<>();
  private final List<String> closed = new ArrayList<>();

  private TestWorkflowEnvironment environment;
  private int maxPerRun;
  private Instant now;

  @BeforeEach
  void setUp() {
    when(alloy.writeHealth()).thenReturn(new AlloyHealthClient.WriteHealth(true, Optional.empty()));
    when(loki.distinctContainers(any(), any())).thenReturn(5);
    when(loki.linesIn(any(), any(), anyInt())).thenReturn(threeLiveProblems());

    when(repository.findByProducerOrderByLastSeenAtDesc("logwatch"))
        .thenAnswer(
            invocation ->
                records.values().stream()
                    .sorted(Comparator.comparing(LinearIssueRecord::lastSeenAt).reversed())
                    .toList());
    when(repository.save(any(LinearIssueRecord.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gateway.teamContext())
        .thenReturn(new LinearGateway.TeamContext("t1", "triage", "done", Map.of()));
    when(gateway.issuesForFingerprint(anyString()))
        .thenAnswer(
            invocation -> {
              String url = invocation.getArgument(0, String.class);
              String fingerprint = url.substring(url.lastIndexOf('/') + 1);
              LinearIssueRecord record = records.get(fingerprint);
              return List.of(
                  new TrackedIssue(
                      record.issueId(), record.issueIdentifier(), record.issueUrl(),
                      IssueStateType.TRIAGE, now));
            });
    doAnswer(
            invocation -> {
              closed.add(invocation.getArgument(0, String.class));
              return null;
            })
        .when(gateway)
        .updateIssue(anyString(), any(), anyString());

    final LinearProperties sink =
        new LinearProperties(true, "k", null, "SIM", null, false, null, null);
    final IssueResolver resolver = new IssueResolver(gateway, repository, sink);

    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(LogWatchTaskQueues.LOG_WATCH);
    worker.registerWorkflowImplementationTypes(LogWatchWorkflowImpl.class);
    worker.registerActivitiesImplementations(new CappedActivities());
    Worker linearWorker = environment.newWorker(LinearTaskQueues.LINEAR);
    linearWorker.registerActivitiesImplementations(new InMemorySink(resolver));
    environment.start();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  @DisplayName("a capped scan closes the stopped problem and keeps what it saw but dropped open")
  void closesWhatStoppedAndKeepsWhatTheCapDropped() {
    // Night one: room for everything, so all three live problems are filed.
    maxPerRun = 5;
    scan("night-one");
    assertThat(records).hasSize(3);
    final List<String> live = List.copyOf(records.keySet());

    // A week and more passes with nothing filed, and a fourth problem stopped long ago.
    records.replaceAll((fingerprint, record) -> aged(record));
    LinearIssueRecord stopped =
        LinearIssueRecord.first(
                Fingerprint.of("logwatch", List.of("backend", "ERROR", "logger:gone")),
                "logwatch",
                List.of("backend", "ERROR", "logger:gone"),
                Instant.now().minus(Duration.ofDays(20)))
            .withIssue("issue-gone", "SIM-99", "https://linear.app/SIM-99");
    records.put(stopped.id(), stopped);

    // Night two: the same three problems, but room for only one. Two are seen and dropped.
    maxPerRun = 1;
    LogWatchResult result = scan("night-two");

    assertThat(result.signaturesDropped()).isEqualTo(2);
    assertThat(result.resolvedIssueUrls()).containsExactly("https://linear.app/SIM-99");
    assertThat(closed).containsExactly("issue-gone");
    // Every live problem's ticket is still open — the filed one because filing advanced it, the
    // two dropped ones because the scan named them.
    live.forEach(
        fingerprint ->
            assertThat(closed).doesNotContain(records.get(fingerprint).issueId()));
    verify(gateway).updateIssue("issue-gone", null, "done");
  }

  private LogWatchResult scan(final String workflowId) {
    now = Instant.now();
    final Instant to = now;
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            LogWatchWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(LogWatchTaskQueues.LOG_WATCH)
                .setWorkflowId(workflowId)
                .build())
        .run(
            new LogWatchRequest(
                to.minus(Duration.ofHours(24)), to, Trigger.SCHEDULE, false, true, true,
                QUIET_FOR));
  }

  private static LinearIssueRecord aged(final LinearIssueRecord record) {
    return LinearIssueRecord.first(
            record.id(), record.producer(), record.keyParts(),
            Instant.now().minus(Duration.ofDays(9)))
        .withIssue(record.issueId(), record.issueIdentifier(), record.issueUrl());
  }

  private static List<LogLine> threeLiveProblems() {
    Instant at = Instant.now();
    List<LogLine> lines = new ArrayList<>();
    for (String raw :
        List.of("ERROR the important one", "WARN thing a failed", "WARN thing b failed")) {
      Severity severity = raw.startsWith("ERROR") ? Severity.ERROR : Severity.WARN;
      lines.add(new LogLine("backend", at, severity, raw));
      lines.add(new LogLine("backend", at, severity, raw));
    }
    return lines;
  }

  /** The real activities, rebuilt on each call so a test can change the cap between scans. */
  private final class CappedActivities implements LogWatchActivities {

    @Override
    public ScanObservation observe(final Instant from, final Instant to) {
      return new LogWatchActivitiesImpl(
              loki,
              alloy,
              mock(LogWatchRunRepository.class),
              new LogWatchProperties(
                  true, 2, maxPerRun, null, 5000, 3, null, null, null, null, List.of()))
          .observe(from, to);
    }

    @Override
    public void recordRun(final LogWatchRunRecord record) {
      // Not under test.
    }
  }

  /**
   * Files by recording what {@code IssueFiler} would — a record keyed on the filing's own
   * fingerprint, seen now — and sweeps through the real resolver.
   */
  private final class InMemorySink implements LinearActivities {

    private final IssueResolver resolver;

    private InMemorySink(final IssueResolver resolver) {
      this.resolver = resolver;
    }

    @Override
    public FiledIssue fileIssue(final IssueFiling filing) {
      String fingerprint = Fingerprint.of(filing.producer(), filing.keyParts());
      LinearIssueRecord existing = records.get(fingerprint);
      String identifier =
          existing == null ? "SIM-" + (records.size() + 1) : existing.issueIdentifier();
      String url = "https://linear.app/" + identifier;
      records.put(
          fingerprint,
          LinearIssueRecord.first(fingerprint, filing.producer(), filing.keyParts(), now)
              .withIssue("issue-" + identifier, identifier, url));
      return new FiledIssue(
          FilingDecision.FILED_NEW, "issue-" + identifier, identifier, url, fingerprint);
    }

    @Override
    public SweepReport sweepResolved(final AbsenceSweep sweep) {
      return resolver.sweep(sweep);
    }

    @Override
    public void attachUrl(final String issueId, final String url, final String title) {
      // Not under test.
    }
  }
}
