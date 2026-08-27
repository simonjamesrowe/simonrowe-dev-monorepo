package com.simonrowe.factory.linear.service;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIssueDecision;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Files one occurrence: fingerprint it, ask Linear what already carries that fingerprint, decide,
 * act, and record.
 *
 * <p>Ordering is deliberate. The Mongo record is written with {@code attachmentPending} between
 * {@code issueCreate} and {@code attachmentCreate}, so a retry after a half-completed filing
 * repairs by attaching rather than by creating a second ticket.
 */
@Component
public class IssueFiler {

  private static final Logger log = LoggerFactory.getLogger(IssueFiler.class);

  private final LinearGateway gateway;
  private final FilingDecider decider;
  private final LinearIssueRepository records;
  private final LinearProperties properties;
  private final Clock clock;

  /**
   * Creates the filer with the system clock.
   *
   * @param gateway the Linear API
   * @param decider the precedence rules
   * @param records the audit collection
   * @param properties the bound {@code factory.linear} configuration
   */
  @Autowired
  public IssueFiler(
      final LinearGateway gateway,
      final FilingDecider decider,
      final LinearIssueRepository records,
      final LinearProperties properties) {
    this(gateway, decider, records, properties, Clock.systemUTC());
  }

  /**
   * Creates the filer with an injectable clock, for tests that need to pin timestamps.
   *
   * @param gateway the Linear API
   * @param decider the precedence rules
   * @param records the audit collection
   * @param properties the bound {@code factory.linear} configuration
   * @param clock the clock to read the current instant from
   */
  IssueFiler(
      final LinearGateway gateway,
      final FilingDecider decider,
      final LinearIssueRepository records,
      final LinearProperties properties,
      final Clock clock) {
    this.gateway = gateway;
    this.decider = decider;
    this.records = records;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Files an occurrence, exactly once per distinct problem.
   *
   * @param filing the occurrence
   * @return what was done
   */
  public FiledIssue file(final IssueFiling filing) {
    Instant now = clock.instant();
    String fingerprint = Fingerprint.of(filing.producer(), filing.keyParts());
    String fingerprintUrl = Fingerprint.urlFor(properties.fingerprintBaseUrl(), fingerprint);

    Optional<LinearIssueRecord> stored = records.findById(fingerprint);

    if (stored.isPresent() && stored.get().hasOccurrence(filing.occurrenceId())) {
      // A Temporal activity retry after a fully successful run. Neither Linear nor Mongo is
      // touched: the previous decision already stands, and repeating it would post a second
      // "seen again" comment for one occurrence.
      LinearIssueRecord existing = stored.get();
      LinearIssueDecision previous = lastDecisionFor(existing, filing.occurrenceId());
      log.info(
          "Occurrence {} already filed for fingerprint {}; treating as a replay",
          filing.occurrenceId(),
          fingerprint);
      return new FiledIssue(
          previous.decision(), existing.issueIdentifier(), existing.issueUrl(), fingerprint);
    }

    LinearIssueRecord record =
        stored.orElseGet(
            () -> LinearIssueRecord.first(fingerprint, filing.producer(), filing.keyParts(), now));

    if (record.attachmentPending() && record.issueId() != null) {
      return repairPendingAttachment(record, filing, fingerprintUrl, now, fingerprint);
    }

    List<TrackedIssue> carrying = gateway.issuesForFingerprint(fingerprintUrl);
    FilingDecider.Outcome outcome = decider.decide(carrying);
    IssueStateType observed = outcome.subject() == null ? null : outcome.subject().stateType();

    if (properties.dryRun()) {
      log.info(
          "Dry run: would {} for fingerprint {} ({} issues carry it)",
          outcome.decision(),
          fingerprint,
          carrying.size());
      return finish(record, outcome.decision(), filing, now, observed, fingerprint);
    }

    LinearProperties.Producer policy = properties.producerFor(filing.producer());
    switch (outcome.decision()) {
      case FILED_NEW ->
          record = createAndAttach(record, filing, filing.body(), policy, fingerprintUrl, null);
      case COMMENTED_EXISTING -> {
        gateway.addComment(outcome.subject().id(), occurrenceComment(filing));
        record =
            record.withIssue(
                outcome.subject().id(), outcome.subject().identifier(), outcome.subject().url());
      }
      case SUPPRESSED ->
          log.info(
              "Fingerprint {} was declined on {}; staying quiet",
              fingerprint,
              outcome.subject().identifier());
      case FILED_REGRESSION ->
          record =
              createAndAttach(
                  record,
                  filing,
                  regressionBody(filing, outcome.subject()),
                  policy,
                  fingerprintUrl,
                  outcome.subject().id());
      default -> throw new IllegalStateException("Unhandled decision " + outcome.decision());
    }

    return finish(record, outcome.decision(), filing, now, observed, fingerprint);
  }

  private FiledIssue repairPendingAttachment(
      final LinearIssueRecord record,
      final IssueFiling filing,
      final String fingerprintUrl,
      final Instant now,
      final String fingerprint) {
    // The known issue id on the record is the authority here, not a fresh lookup: Linear's
    // indexing of the attachment can lag, and a lookup landing between issueCreate and
    // attachmentCreate could legitimately come back empty, which would make the decider file
    // exactly the duplicate this repair exists to prevent.
    log.warn(
        "Repairing a pending fingerprint attachment on {} rather than filing a duplicate",
        record.issueIdentifier());
    if (properties.dryRun()) {
      return finish(record, FilingDecision.COMMENTED_EXISTING, filing, now, null, fingerprint);
    }
    gateway.attachFingerprint(record.issueId(), fingerprintUrl);
    gateway.addComment(record.issueId(), occurrenceComment(filing));
    LinearIssueRecord repaired = record.withAttachmentWritten();
    return finish(repaired, FilingDecision.COMMENTED_EXISTING, filing, now, null, fingerprint);
  }

  private LinearIssueRecord createAndAttach(
      final LinearIssueRecord record,
      final IssueFiling filing,
      final String body,
      final LinearProperties.Producer policy,
      final String fingerprintUrl,
      final String regressedFromIssueId) {
    LinearGateway.CreatedIssue created =
        gateway.createIssue(filing.title(), body, policy.priority(), policy.label());
    // Written BEFORE the attachment, so a failure between the two is recoverable.
    LinearIssueRecord pending =
        record.withPendingAttachment(created.id(), created.identifier(), created.url());
    records.save(pending);
    gateway.attachFingerprint(created.id(), fingerprintUrl);
    if (regressedFromIssueId != null) {
      gateway.relateIssues(created.id(), regressedFromIssueId);
    }
    return pending.withAttachmentWritten();
  }

  private FiledIssue finish(
      final LinearIssueRecord record,
      final FilingDecision decision,
      final IssueFiling filing,
      final Instant now,
      final IssueStateType observed,
      final String fingerprint) {
    LinearIssueRecord saved =
        records.save(
            record.withDecision(
                new LinearIssueDecision(
                    now,
                    decision,
                    filing.occurrenceId(),
                    filing.workflowId(),
                    filing.occurrenceDetail(),
                    properties.dryRun()),
                now,
                observed));
    return new FiledIssue(decision, saved.issueIdentifier(), saved.issueUrl(), fingerprint);
  }

  private static LinearIssueDecision lastDecisionFor(
      final LinearIssueRecord record, final String occurrenceId) {
    return record.decisions().stream()
        .filter(d -> occurrenceId.equals(d.occurrenceId()))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private static String occurrenceComment(final IssueFiling filing) {
    return "Seen again: " + filing.occurrenceDetail();
  }

  private static String regressionBody(final IssueFiling filing, final TrackedIssue predecessor) {
    return filing.body()
        + "\n\n---\n\nThis is a regression of "
        + predecessor.identifier()
        + " ("
        + predecessor.url()
        + "), which was marked complete. Same fingerprint, new occurrence: "
        + filing.occurrenceDetail();
  }
}
