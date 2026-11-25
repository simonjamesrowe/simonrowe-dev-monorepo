package com.simonrowe.school.judge;

import com.simonrowe.school.admin.SchoolApprovalService;
import com.simonrowe.school.ingest.GmailClient;
import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.judge.PublicationJudge.Verdict;
import com.simonrowe.school.judge.SchoolJudgement.Outcome;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides waiting school email into the public or private tier, so the owner does not have to.
 *
 * <p>One pass, the sweep, does everything: it reads the approval queue, judges each email nobody
 * has ruled on, and then lets each waiting attachment follow its email. Run after every mail
 * sync and from the admin page. Working from the queue rather than from ingest means the
 * emails already waiting when this shipped are judged by the same code as new ones.
 *
 * <p>Load-bearing choices:
 *
 * <ul>
 *   <li><b>Every decision goes through {@link SchoolApprovalService}</b>, never a write to
 *       {@code visibility}. That path cascades the tier to the document's events and rewrites
 *       its chunks, without which a published email stays restricted in the search index.</li>
 *   <li><b>Rules outrank the model in one direction only.</b> A rule hit is a decline the model
 *       never sees. No rule ever publishes.</li>
 *   <li><b>Publishing needs a verified sender</b> ({@link SenderAuthentication}). Declining does
 *       not, because a wrong decline costs a click.</li>
 *   <li><b>A person's decision is never re-judged.</b> The sweep only sees documents nobody has
 *       ruled on, and a document the judge already decided keeps that judgement: if the owner
 *       revokes an automatic approval it goes back to the queue for the owner, not to the judge.
 *       A changed text is judged again.</li>
 *   <li><b>The accepted cost:</b> an automatic approval is revocable, but an answer already
 *       served from it is not. Accepted because the rules veto first, publishing needs 0.9
 *       confidence and a verified sender, and the alternative was every school email being
 *       invisible to the assistant for 2 to 7 days while it waited in the queue.</li>
 * </ul>
 */
@Service
public class PublicationJudgeService {

  /** Recorded as {@code approvedBy}, so the admin pages can tell the judge from the owner. */
  public static final String APPROVER = "judge:" + PublicationJudge.VERSION;

  private static final Logger LOG = LoggerFactory.getLogger(PublicationJudgeService.class);
  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final String GMAIL_PREFIX = "gmail:";

  private final SchoolJudgeProperties properties;
  private final SchoolDocumentRepository documents;
  private final SchoolJudgementRepository judgements;
  private final SchoolApprovalService approvals;
  private final PublicationJudge judge;
  private final GmailClient gmail;
  private final Clock clock;
  private final PublicationRules rules;
  private final ReentrantLock running = new ReentrantLock();

  @SuppressWarnings("checkstyle:ParameterNumber")
  public PublicationJudgeService(
      final SchoolJudgeProperties properties,
      final SchoolDocumentRepository documents,
      final SchoolJudgementRepository judgements,
      final SchoolApprovalService approvals,
      final PublicationJudge judge,
      final GmailClient gmail,
      final Clock clock) {
    this.properties = properties;
    this.documents = documents;
    this.judgements = judgements;
    this.approvals = approvals;
    this.judge = judge;
    this.gmail = gmail;
    this.clock = clock;
    this.rules = new PublicationRules(properties);
  }

  /**
   * What one sweep did.
   *
   * @param ran false when the judge is off or a sweep was already running
   * @param approved published
   * @param declined kept private
   * @param queued left for a person
   * @param shadowed recorded without deciding
   * @param skipped could not be judged this time (sender headers unreadable, or an attachment
   *     whose email is still waiting); tried again next sweep
   * @param detail a sentence for the admin page
   */
  public record SweepResult(boolean ran, int approved, int declined, int queued, int shadowed,
      int skipped, String detail) {

    static SweepResult notRun(final String detail) {
      return new SweepResult(false, 0, 0, 0, 0, 0, detail);
    }
  }

  /**
   * Judges everything waiting that has not been judged.
   *
   * @return what was done
   */
  public SweepResult sweep() {
    if (properties.mode() == SchoolJudgeProperties.Mode.OFF) {
      return SweepResult.notRun("The judge is switched off");
    }
    if (!running.tryLock()) {
      return SweepResult.notRun("The judge is already running");
    }
    try {
      return doSweep();
    } finally {
      running.unlock();
    }
  }

  private SweepResult doSweep() {
    final List<SchoolDocument> waiting = documents.findAwaitingApproval();
    final Map<String, SchoolJudgement> previous = judgements
        .findAllById(waiting.stream().map(SchoolDocument::id).toList()).stream()
        .collect(Collectors.toMap(SchoolJudgement::id, Function.identity()));
    final Tally tally = new Tally();
    final LocalDate today = LocalDate.ofInstant(clock.instant(), LONDON);
    try {
      for (SchoolDocument email : waiting) {
        if (email.sourceType() == SchoolSourceType.EMAIL
            && needsJudging(email, previous.get(email.id()))) {
          tally.count(judgeEmail(email, today));
        }
      }
    } catch (GmailClient.GmailAuthException e) {
      LOG.warn("Publication judge stopped: Gmail authentication failed: {}", e.getMessage());
      return new SweepResult(true, tally.approved, tally.declined, tally.queued, tally.shadowed,
          tally.skipped, "Stopped early: Gmail would not authenticate");
    }
    for (SchoolDocument attachment : waiting) {
      if (attachment.sourceType() == SchoolSourceType.PDF
          && attachment.sourceRef() != null && attachment.sourceRef().startsWith(GMAIL_PREFIX)
          && needsJudging(attachment, previous.get(attachment.id()))) {
        tally.count(followEmail(attachment, today));
      }
    }
    final SweepResult result = new SweepResult(true, tally.approved, tally.declined,
        tally.queued, tally.shadowed, tally.skipped,
        "Published %d, kept %d private, left %d for you%s".formatted(
            tally.approved, tally.declined, tally.queued,
            tally.shadowed > 0 ? ", recorded %d in shadow mode".formatted(tally.shadowed) : ""));
    if (tally.total() > 0) {
      LOG.info("Publication judge: {}", result.detail());
    }
    return result;
  }

  /**
   * Whether a waiting document should be judged (again).
   *
   * <p>A document left for a person (QUEUED) is not re-asked every sweep: that would be a model
   * call per queued email every 30 minutes, for as long as it waits, to get the same answer. It
   * is re-asked once when {@link PublicationJudge#VERSION} changes, so a better prompt gets a
   * second look at the marginal cases the last one left behind.
   */
  private boolean needsJudging(final SchoolDocument document, final SchoolJudgement previous) {
    if (previous == null || !Objects.equals(previous.contentHash(), document.contentHash())) {
      return true;
    }
    final boolean auto = properties.mode() == SchoolJudgeProperties.Mode.AUTO;
    return auto && (previous.outcome() == Outcome.SHADOW
        || previous.outcome() == Outcome.QUEUED
            && !PublicationJudge.VERSION.equals(previous.version()));
  }

  private Optional<Outcome> judgeEmail(final SchoolDocument email, final LocalDate today)
      throws GmailClient.GmailAuthException {
    final Optional<List<Map.Entry<String, String>>> headers = gmail.isConfigured()
        ? gmail.fetchSenderHeaders(email.sourceRef())
        : Optional.empty();
    if (headers.isEmpty()) {
      // Without the From header the sender rule would read "unknown address" and decline. A
      // read failure is not evidence, so try again next time instead.
      return Optional.empty();
    }
    final String fromHeader = header(headers.get(), "from").findFirst().orElse("");
    final boolean authenticated = SenderAuthentication.authenticated(
        SenderAuthentication.googleHeader(
            header(headers.get(), "authentication-results").toList()).orElse(null),
        properties.schoolDomain(), properties.trustedEnvelopeSenders());

    final Optional<PublicationRules.Hit> hit = rules.email(
        bareAddress(fromHeader), email.title(), email.body(), email.publishedAt(), today);
    if (hit.isPresent()) {
      return Optional.of(record(email, Verdict.PRIVATE, 1, hit.get().reason(), hit.get().code(),
          null, authenticated, null));
    }
    final PublicationJudge.Answer answer = judge.judge(fromHeader, email.title(), email.body());
    return Optional.of(record(email, answer.verdict(), answer.confidence(), answer.reason(),
        null, null, authenticated, properties.model()));
  }

  /**
   * Lets an attachment follow its email: public with it, private with it, and left alone while
   * the email is still waiting. The owner decided 46 of 49 attachments this way. The attachment
   * rules can only make it more private than its email.
   */
  private Optional<Outcome> followEmail(final SchoolDocument attachment, final LocalDate today) {
    final String ref = attachment.sourceRef().substring(GMAIL_PREFIX.length());
    final int colon = ref.indexOf(':');
    if (colon <= 0) {
      return Optional.empty();
    }
    final String emailId = SchoolIds.documentId(SchoolSourceType.EMAIL, ref.substring(0, colon));
    // Re-read: this sweep may have just decided it.
    final Optional<SchoolDocument> email = documents.findById(emailId);
    if (email.isEmpty()) {
      return Optional.empty();
    }
    final boolean emailPublic = email.get().visibility() == Visibility.PUBLIC;
    final boolean emailDeclined = !emailPublic && email.get().declinedAt() != null;
    final boolean authenticated = judgements.findById(emailId)
        .map(SchoolJudgement::senderAuthenticated).orElse(false);

    final Optional<PublicationRules.Hit> hit =
        rules.attachment(attachment.title(), attachment.body(), attachment.publishedAt(), today);
    if (hit.isPresent()) {
      return Optional.of(record(attachment, Verdict.PRIVATE, 1, hit.get().reason(),
          hit.get().code(), emailId, authenticated, null));
    }
    if (!emailPublic && !emailDeclined) {
      return Optional.empty();
    }
    // `true`, not `authenticated`: an attachment inherits its email's standing rather than being
    // verified afresh. Its email is already public, so either the judge verified the sender or a
    // person approved it, and an email approved by hand has no judgement to read a value from.
    return Optional.of(record(attachment, emailPublic ? Verdict.PUBLIC : Verdict.PRIVATE, 1,
        emailPublic ? "Follows its email, which is public"
            : "Follows its email, which was kept private",
        "follows-email", emailId, true, null));
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private Outcome record(final SchoolDocument document, final Verdict verdict,
      final double confidence, final String reason, final String rule,
      final String followsDocumentId, final boolean authenticated, final String model) {
    final Outcome outcome = outcome(verdict, confidence, rule != null, authenticated);
    judgements.save(new SchoolJudgement(document.id(), document.sourceType(), document.title(),
        document.publishedAt(), document.contentHash(), verdict, confidence, reason, rule,
        followsDocumentId, authenticated, outcome, model, PublicationJudge.VERSION,
        Instant.now(clock)));
    switch (outcome) {
      case APPROVED -> approvals.approve(document.id(), APPROVER);
      case DECLINED -> approvals.decline(document.id());
      default -> {
        // QUEUED stays in the approvals queue; SHADOW decides nothing.
      }
    }
    // Ids and outcomes only: a private email's subject is exactly what must not reach the logs.
    LOG.info("Judged document {}: {} ({}{})", document.id(), outcome, verdict,
        rule == null ? "" : ", rule " + rule);
    return outcome;
  }

  /**
   * Turns a verdict into an action. Package-private so the thresholds are tested directly.
   *
   * @param verdict the verdict
   * @param confidence its confidence
   * @param fromRule whether a rule decided it
   * @param authenticated whether the sender is verified
   * @return what to do
   */
  Outcome outcome(final Verdict verdict, final double confidence, final boolean fromRule,
      final boolean authenticated) {
    if (properties.mode() != SchoolJudgeProperties.Mode.AUTO) {
      return Outcome.SHADOW;
    }
    if (verdict == Verdict.PRIVATE && (fromRule || confidence >= properties.declineThreshold())) {
      return Outcome.DECLINED;
    }
    if (verdict == Verdict.PUBLIC && authenticated
        && confidence >= properties.approveThreshold()) {
      return Outcome.APPROVED;
    }
    return Outcome.QUEUED;
  }

  private static java.util.stream.Stream<String> header(
      final List<Map.Entry<String, String>> headers, final String name) {
    return headers.stream()
        .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
        .map(Map.Entry::getValue);
  }

  private static String bareAddress(final String from) {
    final int open = from.indexOf('<');
    final int close = from.indexOf('>', open + 1);
    final String address = open >= 0 && close > open ? from.substring(open + 1, close) : from;
    return address.trim().toLowerCase(Locale.ROOT);
  }

  private static final class Tally {
    private int approved;
    private int declined;
    private int queued;
    private int shadowed;
    private int skipped;

    void count(final Optional<Outcome> outcome) {
      if (outcome.isEmpty()) {
        skipped++;
        return;
      }
      switch (outcome.get()) {
        case APPROVED -> approved++;
        case DECLINED -> declined++;
        case QUEUED -> queued++;
        case SHADOW -> shadowed++;
        default -> throw new IllegalStateException("Unknown outcome " + outcome.get());
      }
    }

    int total() {
      return approved + declined + queued + shadowed;
    }
  }
}
