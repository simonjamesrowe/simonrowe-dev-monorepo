package com.simonrowe.factory.linear.service;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.FilingMode;
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
      return reported(
          previous.decision(), existing.issueId(), existing.issueIdentifier(), existing.issueUrl(),
          fingerprint);
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
    // Resolved once, before the dry-run branch, so a preview reports the exact decision a real
    // run would take: a filing whose mode may not create an issue (STATUS_UPDATE) must never
    // create one, and that includes the FILED_NEW/FILED_REGRESSION arms the decider would
    // otherwise pick.
    FilingDecision effective = applyMode(outcome.decision(), filing.mode());

    if (properties.dryRun()) {
      log.info(
          "Dry run: would {} for fingerprint {} ({} issues carry it)",
          effective,
          fingerprint,
          carrying.size());
      return finish(record, effective, filing, now, observed, fingerprint);
    }

    LinearProperties.Producer policy = properties.producerFor(filing.producer());
    switch (effective) {
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
      case UPDATED_EXISTING -> {
        gateway.updateIssue(outcome.subject().id(), filing.body(), null);
        record =
            record.withIssue(
                outcome.subject().id(), outcome.subject().identifier(), outcome.subject().url());
      }
      case REOPENED_EXISTING -> {
        // The Triage state, not "whatever state it was in before": Linear does not record that,
        // and Triage is where a newly filed ticket lands — so a reopened rolling report re-enters
        // the queue a human actually watches rather than appearing somewhere nobody looks.
        gateway.updateIssue(
            outcome.subject().id(), filing.body(), gateway.teamContext().triageStateId());
        record =
            record.withIssue(
                outcome.subject().id(), outcome.subject().identifier(), outcome.subject().url());
      }
      case SKIPPED_NO_ISSUE ->
          // A filing whose mode may not create (STATUS_UPDATE) that resolved to FILED_NEW or
          // FILED_REGRESSION is deliberately reduced to this instead: no open issue exists to
          // comment on, and creating one — or filing a "recurrence" whose actual content is the
          // ABSENCE of the problem — would be worse than silence.
          log.info(
              "Fingerprint {} has no open issue to comment on; a comment-only filing does"
                  + " nothing",
              fingerprint);
      default -> throw new IllegalStateException("Unhandled decision " + effective);
    }

    return finish(record, effective, filing, now, observed, fingerprint);
  }

  /**
   * Maps the decision {@link FilingDecider} reached onto the one this producer's mode asks for.
   *
   * <p>This is the only place a mode is interpreted. {@code FilingDecider} answers what Linear
   * currently says about the fingerprint and stays pure; the mode decides what to do about that
   * answer.
   *
   * @param decided the decision {@link FilingDecider} reached
   * @param mode the producer's filing mode
   * @return the decision the sink will honour
   */
  private static FilingDecision applyMode(final FilingDecision decided, final FilingMode mode) {
    // Where a mode that must never create an issue meets a decision that would create one, the
    // answer is SKIPPED_NO_ISSUE: no open issue exists to comment on, and creating one — or
    // filing a "recurrence" whose actual content is the ABSENCE of the problem — is worse than
    // silence. Unchanged from 040.
    return switch (decided) {
      case COMMENTED_EXISTING ->
          mode.rewritesBody()
              ? FilingDecision.UPDATED_EXISTING
              : FilingDecision.COMMENTED_EXISTING;
      case FILED_REGRESSION -> {
        if (mode.reopensCompleted()) {
          yield FilingDecision.REOPENED_EXISTING;
        }
        yield mode.mayCreate()
            ? FilingDecision.FILED_REGRESSION
            : FilingDecision.SKIPPED_NO_ISSUE;
      }
      case FILED_NEW ->
          mode.mayCreate() ? FilingDecision.FILED_NEW : FilingDecision.SKIPPED_NO_ISSUE;
      default -> decided;
    };
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
    FilingDecision decision =
        filing.mode().rewritesBody()
            ? FilingDecision.UPDATED_EXISTING
            : FilingDecision.COMMENTED_EXISTING;
    if (properties.dryRun()) {
      return finish(record, decision, filing, now, null, fingerprint);
    }
    gateway.attachFingerprint(record.issueId(), fingerprintUrl);
    if (filing.mode().rewritesBody()) {
      gateway.updateIssue(record.issueId(), filing.body(), null);
    } else {
      gateway.addComment(record.issueId(), occurrenceComment(filing));
    }
    LinearIssueRecord repaired = record.withAttachmentWritten();
    return finish(repaired, decision, filing, now, null, fingerprint);
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
    return reported(
        decision, saved.issueId(), saved.issueIdentifier(), saved.issueUrl(), fingerprint);
  }

  /**
   * The answer handed back to the producer, which is deliberately not a mirror of what was
   * persisted.
   *
   * <p><strong>A {@code SUPPRESSED} occurrence reports no issue at all.</strong> The suppression
   * arm leaves the stored record pointing at whatever it last filed, and that is correct for an
   * audit trail — but it makes a reachable, ordinary sequence lie to the producer: occurrence 1
   * files {@code SIM-42}, a human cancels {@code SIM-42}, occurrence 2 resolves to
   * {@code SUPPRESSED} and would otherwise be handed {@code SIM-42} back. The deploy producer
   * puts that URL on {@code DeployRunRecord.issueUrl} and writes a commit comment reading
   * "tracked in Linear", pointing a reader at a declined ticket that never received this
   * occurrence's diagnosis. "We stayed quiet" and "here is the ticket" are different facts, so
   * only the first is reported. Mongo still holds the history.
   *
   * <p><strong>A {@code SKIPPED_NO_ISSUE} occurrence reports no issue either, for the identical
   * reason.</strong> It is reached only for a filing whose {@link FilingMode#mayCreate()} is
   * false (today, {@code STATUS_UPDATE}) whose fingerprint the decider resolved to
   * {@code FILED_NEW} (nothing carries it) or {@code FILED_REGRESSION} (only a completed issue
   * carries it) — in the regression case the stored record still carries that completed issue's
   * id, identifier and URL from whenever it was originally filed, because the non-creating
   * short-circuit never mutates it. Reporting that back would hand a "the repository is clean"
   * producer a real, resolved issue URL for a decision that touched nothing. Same fix as
   * {@code SUPPRESSED}: clear the issue fields on the way out; Mongo still holds the history.
   *
   * @param decision the decision taken
   * @param issueIdentifier the stored identifier, or null
   * @param issueUrl the stored URL, or null
   * @param fingerprint this problem's fingerprint
   * @return the outcome, with the issue fields cleared for a suppressed or skipped occurrence
   */
  private static FiledIssue reported(
      final FilingDecision decision,
      final String issueId,
      final String issueIdentifier,
      final String issueUrl,
      final String fingerprint) {
    if (decision == FilingDecision.SUPPRESSED || decision == FilingDecision.SKIPPED_NO_ISSUE) {
      return new FiledIssue(decision, null, null, null, fingerprint);
    }
    return new FiledIssue(decision, issueId, issueIdentifier, issueUrl, fingerprint);
  }

  private static LinearIssueDecision lastDecisionFor(
      final LinearIssueRecord record, final String occurrenceId) {
    // Filtering on occurrenceId alone, with no dry-run filter, is safe: a dry-run entry can never
    // FOLLOW a real one for the same occurrenceId, because hasOccurrence would have returned true
    // and this method would never have been reached. So the last entry for the id is the real one.
    return record.decisions().stream()
        .filter(d -> occurrenceId.equals(d.occurrenceId()))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private static String occurrenceComment(final IssueFiling filing) {
    // A status update is not a recurrence, so it must not be announced as one: "Seen again: no
    // current vulnerabilities" says the opposite of what it means.
    return filing.mode() == FilingMode.STATUS_UPDATE
        ? filing.occurrenceDetail()
        : "Seen again: " + filing.occurrenceDetail();
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
