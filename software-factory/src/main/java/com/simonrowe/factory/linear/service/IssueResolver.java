package com.simonrowe.factory.linear.service;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.domain.SweptIssue;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIssueDecision;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Closes the issues a producer has stopped reporting — the other half of {@link IssueFiler}.
 *
 * <p>Filing without resolving is only half an automation: it opens tickets forever and leaves a
 * human to notice, one at a time, that each problem has gone away. That manual step is what this
 * removes, and it is the reason a fixed problem's ticket now closes itself on the next clean scan
 * instead of sitting in Triage indefinitely.
 *
 * <h2>What it will not do</h2>
 *
 * <p><strong>It never touches an issue a human has started.</strong> A candidate in
 * {@link IssueStateType#STARTED} is left exactly as it is, and counted separately. Someone is
 * mid-fix; the logs going quiet is very often <em>because</em> of what they are doing — a service
 * stopped, a config reverted, a branch deployed — and closing their ticket underneath them would
 * be the automation overruling the person. Triage, backlog and unstarted issues are fair game
 * precisely because nobody has claimed them.
 *
 * <p><strong>It never crosses producers.</strong> The sweep is scoped by producer key, so a
 * log-watch scan cannot close a {@code cvefix} or {@code deploy} ticket, whose absence means
 * something entirely different (or, for a one-shot deploy failure, means nothing at all).
 *
 * <p><strong>It never invents a state.</strong> When the team has no {@code completed}-type
 * workflow state the sweep reports {@link SweepReport#unavailable} rather than picking some other
 * state to park issues in. That is a visible "cannot", not a silent "did nothing" — the two look
 * identical from a caller that only counts closures.
 *
 * <h2>Getting it wrong is recoverable, and deliberately so</h2>
 *
 * <p>A wrongly closed ticket is not lost work. The fingerprint attachment survives closure, so
 * the next occurrence resolves through {@link FilingDecider} to {@code FILED_REGRESSION} (or,
 * for a rolling producer, {@code REOPENED_EXISTING}) — a new issue linked to the one this closed,
 * saying so. That safety net is what makes a quiet period of days, rather than weeks, a
 * reasonable threshold: the cost of being early is one extra linked ticket, not a lost report.
 * Cancelling, by contrast, would be a poor choice here — the sink treats a cancelled issue as
 * "never tell me again", so an automatic cancel would permanently suppress a problem that had
 * merely paused.
 */
@Component
public class IssueResolver {

  private static final Logger log = LoggerFactory.getLogger(IssueResolver.class);

  private final LinearGateway gateway;
  private final LinearIssueRepository records;
  private final LinearProperties properties;
  private final Clock clock;

  /**
   * Creates the resolver with the system clock.
   *
   * @param gateway the Linear API
   * @param records the audit collection
   * @param properties the bound {@code factory.linear} configuration
   */
  @Autowired
  public IssueResolver(
      final LinearGateway gateway,
      final LinearIssueRepository records,
      final LinearProperties properties) {
    this(gateway, records, properties, Clock.systemUTC());
  }

  /**
   * Creates the resolver with an injectable clock, for tests that need to pin timestamps.
   *
   * @param gateway the Linear API
   * @param records the audit collection
   * @param properties the bound {@code factory.linear} configuration
   * @param clock the clock to read the current instant from
   */
  IssueResolver(
      final LinearGateway gateway,
      final LinearIssueRepository records,
      final LinearProperties properties,
      final Clock clock) {
    this.gateway = gateway;
    this.records = records;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Closes every issue this producer has not reported for {@link AbsenceSweep#quietFor}.
   *
   * @param sweep the request
   * @return what was done
   */
  public SweepReport sweep(final AbsenceSweep sweep) {
    Instant now = clock.instant();
    // EITHER flag suppresses the write, and both are needed. `factory.linear.dry-run` is the
    // sink's own standing configuration; `sweep.dryRun()` is one caller asking for a preview on
    // one run — the console's "Dry run scan" button, whose API response promises that nothing
    // will be filed. Consulting only the configured flag makes that promise a lie on a stack
    // where the sink is (correctly) configured to write.
    boolean preview = sweep.dryRun() || properties.dryRun();
    Instant cutoff = now.minus(sweep.quietFor());

    // Ordered newest-first by the repository, so the candidates are the tail. Iterating the whole
    // producer's history rather than querying on lastSeenAt keeps the read on the existing
    // finder: this collection holds one document per distinct problem ever seen, which is tens,
    // not millions.
    List<LinearIssueRecord> candidates =
        records.findByProducerOrderByLastSeenAtDesc(sweep.producer()).stream()
            .filter(record -> record.lastSeenAt() != null && record.lastSeenAt().isBefore(cutoff))
            .toList();

    if (candidates.isEmpty()) {
      return SweepReport.none();
    }

    String completedStateId = gateway.teamContext().completedStateId();
    if (completedStateId == null) {
      log.warn(
          "{} fingerprint(s) have been quiet since {} but team {} has no completed workflow"
              + " state, so nothing can be closed",
          candidates.size(),
          cutoff,
          properties.teamKey());
      return SweepReport.unavailable(candidates.size());
    }

    List<SweptIssue> resolved = new ArrayList<>();
    int skippedStarted = 0;
    int alreadyClosed = 0;
    int untracked = 0;

    for (LinearIssueRecord record : candidates) {
      if (record.issueId() == null) {
        // Filed only on a dry run, or a create that never completed. Nothing exists to close.
        untracked++;
        continue;
      }

      // Linear is truth, exactly as it is for filing: the stored lastKnownStateType is only ever
      // as fresh as the last filing, and for a fingerprint that has gone quiet that is by
      // definition stale. Reading it back is what stops the sweep commenting on a ticket a human
      // closed last week.
      TrackedIssue subject = currentState(record);
      if (subject == null) {
        untracked++;
        continue;
      }
      if (!subject.stateType().open()) {
        alreadyClosed++;
        continue;
      }
      if (subject.stateType() == IssueStateType.STARTED) {
        log.info(
            "{} has been quiet since before {} but someone is working on it; leaving it alone",
            subject.identifier(),
            cutoff);
        skippedStarted++;
        continue;
      }

      if (preview) {
        log.info("Dry run: would close {} as no longer reported", subject.identifier());
      } else {
        // Comment first, then close. The other order leaves a window in which the ticket is
        // closed with no explanation at all, and that window is exactly when a human reading
        // their Linear inbox sees it.
        gateway.addComment(subject.id(), sweep.comment());
        gateway.updateIssue(subject.id(), null, completedStateId);
      }

      resolved.add(
          new SweptIssue(record.id(), record.keyParts(), subject.identifier(), subject.url()));
      records.save(
          record
              .withIssue(subject.id(), subject.identifier(), subject.url())
              .withDecision(
                  new LinearIssueDecision(
                      now,
                      FilingDecision.RESOLVED_ABSENT,
                      sweep.occurrenceId(),
                      sweep.workflowId(),
                      "No longer reported; quiet since " + record.lastSeenAt(),
                      preview),
                  // lastSeenAt is deliberately NOT advanced to now. It means "when this problem
                  // was last observed", and the sweep observed its absence. Advancing it would
                  // reset the quiet clock and, worse, make the record claim the problem was
                  // happening at the moment it was declared gone.
                  record.lastSeenAt(),
                  IssueStateType.COMPLETED));
    }

    return new SweepReport(
        candidates.size(), resolved, skippedStarted, alreadyClosed, untracked, false);
  }

  /**
   * Re-reads what Linear currently says about the issue on this record.
   *
   * <p>Looked up by the record's own fingerprint attachment rather than by issue id, so it goes
   * through the same query the filing path uses and sees the same set of issues. When several
   * carry the fingerprint — the regression case, where a completed issue and its replacement both
   * do — the one the record points at is the subject; anything else belongs to a different
   * occurrence and is not this sweep's business.
   *
   * @param record the stored record
   * @return the issue as Linear currently reports it, or null when Linear no longer has it
   */
  private TrackedIssue currentState(final LinearIssueRecord record) {
    String fingerprintUrl =
        Fingerprint.urlFor(properties.fingerprintBaseUrl(), record.id());
    return gateway.issuesForFingerprint(fingerprintUrl).stream()
        .filter(issue -> issue.id().equals(record.issueId()))
        .findFirst()
        .orElse(null);
  }
}
