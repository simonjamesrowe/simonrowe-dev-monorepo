package com.simonrowe.coparent.school;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.DocumentSchoolOffer;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.school.ingest.GmailClient;
import com.simonrowe.school.ingest.GmailMessage;
import com.simonrowe.school.ingest.SchoolEventsRefreshed;
import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Offers the letters Term Time reads to families' document libraries, after every school sync
 * ("From school", spec 057 §4). It only reads Term Time; it never changes a school document's
 * tier or approval.
 *
 * <p>Three kinds of offer, from the school documents of the last
 * {@link SchoolDocumentOfferProperties#lookbackDays()} days:
 *
 * <ul>
 *   <li><b>Broadcast letters</b>: a PDF attachment of an email in Term Time's public tier,
 *       matched to linked children by the school-events rules ({@link SchoolEventMatcher}), and
 *       offered only when {@link SchoolLetterClassifier} calls it keepable. A clear "not worth
 *       keeping" is recorded as a {@code DISMISSED} offer nobody decided, so the letter is not
 *       sent to the model again; a classifier failure records nothing and is retried.</li>
 *   <li><b>Personal letters</b>: a restricted PDF attachment, for the family whose mailbox Term
 *       Time reads only, matched by {@link SchoolLetterRules#childrenNamed}.</li>
 *   <li><b>Report prompts</b>: "A report for Mia is ready" from the reporting system, for the
 *       mailbox family only, with a link accepted only on its exact origin. Term Time keeps no
 *       sender address on a document, so the sender is read back from Gmail for the few emails
 *       whose subject already matched.</li>
 * </ul>
 *
 * <p>The unique {@code (familyId, schoolDocumentId)} index is the ledger: every offer is an
 * insert-if-absent, so nothing is offered twice, whatever was decided. A letter for two children
 * is one offer for both. Only PDFs are offered: Term Time stores no other attachment type, and
 * the library accepts no other document type.
 *
 * <p>New offers add nothing to the documents batch email. That notice lists documents a parent
 * changed and is never sent to the parent who acted, neither of which fits an offer, and the
 * school-events mailer already emails the family after a school sync.
 *
 * <p>Runs on its own thread, one run at a time, like {@link SchoolSuggestionSync}.
 */
@Component
public class SchoolDocumentOfferSync {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolDocumentOfferSync.class);
  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final String GMAIL_PREFIX = "gmail:";
  private static final String REPORT_SENDER_NAME = "Insight Tracking";
  private static final String REPORT_REASON =
      "The school publishes reports on Insight Tracking, not by email.";

  private final SchoolDocumentRepository schoolDocuments;
  private final MongoTemplate coparent;
  private final SchoolLetterClassifier classifier;
  private final GmailClient gmail;
  private final CoparentProperties coparentProperties;
  private final SchoolCalendarProperties calendarProperties;
  private final SchoolDocumentOfferProperties properties;
  private final Clock clock;
  private final ReentrantLock running = new ReentrantLock();
  private final AtomicBoolean queued = new AtomicBoolean();
  private final ExecutorService worker = Executors.newSingleThreadExecutor(
      Thread.ofVirtual().name("school-document-offers").factory());

  @SuppressWarnings("checkstyle:ParameterNumber")
  public SchoolDocumentOfferSync(
      final SchoolDocumentRepository schoolDocuments,
      @Qualifier("coparentMongoTemplate") final MongoTemplate coparent,
      final SchoolLetterClassifier classifier,
      final GmailClient gmail,
      final CoparentProperties coparentProperties,
      final SchoolCalendarProperties calendarProperties,
      final SchoolDocumentOfferProperties properties,
      final Clock clock) {
    this.schoolDocuments = schoolDocuments;
    this.coparent = coparent;
    this.classifier = classifier;
    this.gmail = gmail;
    this.coparentProperties = coparentProperties;
    this.calendarProperties = calendarProperties;
    this.properties = properties;
    this.clock = clock;
  }

  /** What one run did, for the log and for tests. */
  public record Report(int offered, int notOffered, int classified) {
    static final Report NOTHING = new Report(0, 0, 0);

    Report plus(final Report other) {
      return new Report(offered + other.offered, notOffered + other.notOffered,
          classified + other.classified);
    }
  }

  /** Runs after each school sync. */
  @EventListener
  public void onSchoolEventsRefreshed(final SchoolEventsRefreshed refreshed) {
    request();
  }

  /** Asks for a run on the worker thread, unless one is already waiting to start. */
  public void request() {
    if (!enabled() || !queued.compareAndSet(false, true)) {
      return;
    }
    worker.execute(() -> {
      queued.set(false);
      try {
        run();
      } catch (RuntimeException failure) {
        LOG.warn("School document offers failed: {}", failure.getMessage(), failure);
      }
    });
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  /**
   * Offers what is new to every family, now, on the calling thread.
   *
   * @return what it did
   */
  public Report run() {
    if (!enabled()) {
      return Report.NOTHING;
    }
    running.lock();
    try {
      return runLocked();
    } finally {
      running.unlock();
    }
  }

  private Report runLocked() {
    final Instant now = clock.instant();
    final ObjectId mailboxFamily = ObjectId.isValid(calendarProperties.mailboxFamilyId())
        ? new ObjectId(calendarProperties.mailboxFamilyId()) : null;
    final Map<ObjectId, List<Child>> linked = coparent.find(Query.query(
            Criteria.where("schoolCalendar").ne(null).and("deletedAt").is(null)), Child.class)
        .stream()
        .collect(Collectors.groupingBy(Child::familyId, LinkedHashMap::new, Collectors.toList()));
    final List<Child> mailboxChildren = mailboxFamily == null ? List.of()
        : coparent.find(Query.query(Criteria.where("familyId").is(mailboxFamily)
            .and("deletedAt").is(null)), Child.class);
    if (linked.isEmpty() && mailboxChildren.isEmpty()) {
      return Report.NOTHING;
    }
    final List<SchoolDocument> window = schoolDocuments.findPublishedBetween(
        List.of(Visibility.PUBLIC, Visibility.RESTRICTED),
        List.of(SchoolSourceType.EMAIL, SchoolSourceType.PDF),
        now.minus(Duration.ofDays(properties.lookbackDays())), now);
    if (window.isEmpty()) {
      return Report.NOTHING;
    }
    final Map<String, SchoolDocument> emails = new HashMap<>();
    window.stream().filter(doc -> doc.sourceType() == SchoolSourceType.EMAIL)
        .forEach(doc -> emails.put(doc.id(), doc));
    final Run run = new Run(window, emails, now);

    final Set<ObjectId> families = new LinkedHashSet<>(linked.keySet());
    if (!mailboxChildren.isEmpty()) {
      families.add(mailboxFamily);
    }
    Report total = Report.NOTHING;
    for (final ObjectId familyId : families) {
      try {
        final boolean mailbox = familyId.equals(mailboxFamily);
        total = total.plus(syncFamily(run, familyId, linked.getOrDefault(familyId, List.of()),
            mailbox ? mailboxChildren : List.of(), mailbox));
      } catch (RuntimeException failure) {
        LOG.warn("School document offers failed for one family: {}", failure.getMessage(),
            failure);
      }
    }
    total = new Report(total.offered(), total.notOffered(), run.classified);
    if (total.offered() + total.notOffered() > 0) {
      LOG.info("School document offers: {}", total);
    }
    return total;
  }

  /** State shared by every family in one run: the window, and the classifier's answers. */
  private static final class Run {
    private final List<SchoolDocument> window;
    private final Map<String, SchoolDocument> emails;
    private final Instant now;
    private final Map<String, Optional<SchoolLetterClassifier.Verdict>> verdicts =
        new HashMap<>();
    private int classified;
    private boolean gmailUnavailable;

    Run(final List<SchoolDocument> window, final Map<String, SchoolDocument> emails,
        final Instant now) {
      this.window = window;
      this.emails = emails;
      this.now = now;
    }
  }

  private Report syncFamily(final Run run, final ObjectId familyId,
      final List<Child> linkedChildren, final List<Child> mailboxChildren,
      final boolean mailbox) {
    final Set<String> offered = new HashSet<>();
    coparent.find(Query.query(Criteria.where("familyId").is(familyId).and("schoolDocumentId")
            .in(run.window.stream().map(SchoolDocument::id).toList())),
        DocumentSchoolOffer.class).forEach(offer -> offered.add(offer.schoolDocumentId()));
    int added = 0;
    int declined = 0;
    for (final SchoolDocument document : run.window) {
      if (offered.contains(document.id())) {
        continue;
      }
      final Optional<DocumentSchoolOffer> offer;
      if (isEmailAttachment(document) && document.visibility() == Visibility.PUBLIC) {
        offer = broadcast(run, familyId, document, linkedChildren);
      } else if (mailbox && isEmailAttachment(document)) {
        offer = personal(run, familyId, document, mailboxChildren);
      } else if (mailbox && document.sourceType() == SchoolSourceType.EMAIL) {
        offer = report(run, familyId, document, mailboxChildren);
      } else {
        offer = Optional.empty();
      }
      if (offer.isPresent() && insert(offer.get())) {
        if (offer.get().status() == DocumentSchoolOffer.Status.PENDING) {
          added++;
        } else {
          declined++;
        }
      }
    }
    return new Report(added, declined, 0);
  }

  // ---- broadcast letters -------------------------------------------------------------------

  private Optional<DocumentSchoolOffer> broadcast(final Run run, final ObjectId familyId,
      final SchoolDocument pdf, final List<Child> children) {
    if (children.isEmpty()) {
      return Optional.empty();
    }
    final SchoolDocument email = emailOf(run, pdf);
    final String subject = email == null ? "" : email.title();
    final LocalDate on = dateOf(pdf.publishedAt(), run.now);
    final List<String> tagged = !pdf.yearGroups().isEmpty() ? pdf.yearGroups()
        : email == null ? List.of() : email.yearGroups();
    final String matchText = subject + " " + nullToEmpty(pdf.title());
    final List<Child> matched = children.stream()
        .filter(child -> SchoolEventMatcher.isFor(on, tagged, matchText, child)).toList();
    if (matched.isEmpty()) {
      return Optional.empty();
    }
    final Optional<SchoolLetterClassifier.Verdict> verdict = verdict(run, pdf, subject, email);
    if (verdict.isEmpty()) {
      return Optional.empty();
    }
    final List<String> years = tagged.isEmpty()
        ? List.copyOf(SchoolEventText.yearGroupsNamed(matchText)) : tagged;
    final boolean keep = verdict.get().keep();
    return Optional.of(offer(familyId, pdf, DocumentSchoolOffer.Kind.LETTER, matched,
        "medical".equals(verdict.get().kind()) ? DocumentSection.HEALTH : DocumentSection.SCHOOL,
        SchoolLetterRules.letterTitle(subject, pdf.title()), false, null,
        keep ? DocumentSchoolOffer.Status.PENDING : DocumentSchoolOffer.Status.DISMISSED,
        properties.schoolName(),
        keep ? broadcastReason(years, matched, on)
            : "Not offered: " + verdict.get().reason(),
        pdf.title(), null, null, run.now));
  }

  /** The classifier's answer for a letter, asked once a run and at most N times a run. */
  private Optional<SchoolLetterClassifier.Verdict> verdict(final Run run,
      final SchoolDocument pdf, final String subject, final SchoolDocument email) {
    final Optional<SchoolLetterClassifier.Verdict> known = run.verdicts.get(pdf.id());
    if (known != null) {
      return known;
    }
    if (run.classified >= properties.maxClassifiedPerRun()) {
      return Optional.empty();
    }
    run.classified++;
    final Optional<SchoolLetterClassifier.Verdict> answer = classifier.classify(
        new SchoolLetterClassifier.Letter(subject, pdf.title(), pdf.body(),
            email == null ? null : email.body()));
    run.verdicts.put(pdf.id(), answer);
    return answer;
  }

  static String broadcastReason(final List<String> years, final List<Child> children,
      final LocalDate on) {
    final StringBuilder reason = new StringBuilder(years.isEmpty()
        ? "Sent to every family."
        : "Sent to " + joinAnd(years) + " parents.");
    for (final Child child : children) {
      final Optional<String> year = ChildSchoolYear.yearGroupOf(child, on);
      if (year.isEmpty()) {
        continue;
      }
      reason.append(' ').append(SchoolLetterRules.firstName(child)).append(" is in ")
          .append(year.get());
      ChildSchoolYear.classOf(child, on)
          .ifPresent(name -> reason.append(" (").append(name).append("'s class)"));
      reason.append('.');
    }
    return reason.toString();
  }

  // ---- personal letters --------------------------------------------------------------------

  private Optional<DocumentSchoolOffer> personal(final Run run, final ObjectId familyId,
      final SchoolDocument pdf, final List<Child> children) {
    if (children.isEmpty()) {
      return Optional.empty();
    }
    final SchoolDocument email = emailOf(run, pdf);
    final String subject = email == null ? "" : email.title();
    final LocalDate on = dateOf(pdf.publishedAt(), run.now);
    final List<Child> named = SchoolLetterRules.childrenNamed(subject, pdf.title(), children, on);
    if (named.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(offer(familyId, pdf, DocumentSchoolOffer.Kind.LETTER, named,
        DocumentSection.SCHOOL, SchoolLetterRules.letterTitle(subject, pdf.title()), true, null,
        DocumentSchoolOffer.Status.PENDING, properties.schoolName(),
        "Addressed to you and names " + joinAnd(named.stream()
            .map(SchoolLetterRules::firstName).toList()) + ".",
        pdf.title(), null, null, run.now));
  }

  // ---- report prompts ----------------------------------------------------------------------

  private Optional<DocumentSchoolOffer> report(final Run run, final ObjectId familyId,
      final SchoolDocument email, final List<Child> children) {
    final Optional<Child> child = SchoolLetterRules.reportFirstName(email.title())
        .flatMap(name -> SchoolLetterRules.reportChild(name, children));
    if (child.isEmpty() || run.gmailUnavailable) {
      return Optional.empty();
    }
    final Optional<GmailMessage> message = fetch(run, email.sourceRef());
    if (message.isEmpty()) {
      // Unknown, not refused: asked again next sync.
      return Optional.empty();
    }
    final LocalDate sentOn = dateOf(email.publishedAt(), run.now);
    final String title = SchoolLetterRules.reportTitle(sentOn);
    if (!SchoolLetterRules.REPORT_SENDER.equalsIgnoreCase(message.get().fromAddress())) {
      return Optional.of(offer(familyId, email, DocumentSchoolOffer.Kind.REPORT_READY,
          List.of(child.get()), DocumentSection.SCHOOL, title, true, null,
          DocumentSchoolOffer.Status.DISMISSED, REPORT_SENDER_NAME,
          "Not offered: not sent by the reporting system", null, null, null, run.now));
    }
    // GmailMessage's links are a third-party value; a null list is read as none.
    final List<String> links = message.get().links() == null ? List.of()
        : message.get().links().stream().map(GmailMessage.Link::url).toList();
    final String link = SchoolLetterRules.reportLink(email.body(), links).orElse(null);
    return Optional.of(offer(familyId, email, DocumentSchoolOffer.Kind.REPORT_READY,
        List.of(child.get()), DocumentSection.SCHOOL, title, true, link,
        DocumentSchoolOffer.Status.PENDING, REPORT_SENDER_NAME, REPORT_REASON, null, title,
        sentOn, run.now));
  }

  private Optional<GmailMessage> fetch(final Run run, final String messageId) {
    if (!gmail.isConfigured()) {
      run.gmailUnavailable = true;
      return Optional.empty();
    }
    try {
      final Optional<GmailMessage> message = gmail.fetchMessage(messageId);
      // Kept even though the client never returns null: a future client version might.
      return message == null ? Optional.empty() : message;
    } catch (GmailClient.GmailAuthException failure) {
      run.gmailUnavailable = true;
      LOG.warn("Report emails were not checked: Gmail would not authenticate");
      return Optional.empty();
    }
  }

  // ---- shared ------------------------------------------------------------------------------

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static DocumentSchoolOffer offer(final ObjectId familyId, final SchoolDocument source,
      final DocumentSchoolOffer.Kind kind, final List<Child> children,
      final DocumentSection section, final String title, final boolean personal,
      final String reportLink, final DocumentSchoolOffer.Status status, final String sender,
      final String reason, final String attachmentName, final String suggestedTitle,
      final LocalDate suggestedDatedOn, final Instant now) {
    final boolean decided = status != DocumentSchoolOffer.Status.PENDING;
    return new DocumentSchoolOffer(null, familyId, source.id(), kind,
        children.stream().map(Child::id).distinct().toList(), section, title,
        source.publishedAt(), personal, reportLink, status, null, null, decided ? now : null,
        now, now, sender, reason, attachmentName, suggestedTitle, suggestedDatedOn);
  }

  private boolean insert(final DocumentSchoolOffer offer) {
    try {
      coparent.insert(offer);
      return true;
    } catch (DuplicateKeyException raced) {
      // Another run offered it first; that row stands.
      return false;
    }
  }

  private SchoolDocument emailOf(final Run run, final SchoolDocument attachment) {
    final String ref = attachment.sourceRef().substring(GMAIL_PREFIX.length());
    final int colon = ref.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    final String emailId = SchoolIds.documentId(SchoolSourceType.EMAIL, ref.substring(0, colon));
    final SchoolDocument known = run.emails.get(emailId);
    return known != null ? known : schoolDocuments.findById(emailId).orElse(null);
  }

  private static boolean isEmailAttachment(final SchoolDocument document) {
    return document.sourceType() == SchoolSourceType.PDF && document.sourceRef() != null
        && document.sourceRef().startsWith(GMAIL_PREFIX);
  }

  private static LocalDate dateOf(final Instant at, final Instant fallback) {
    return LocalDate.ofInstant(at == null ? fallback : at, LONDON);
  }

  static String joinAnd(final List<String> items) {
    if (items.size() <= 1) {
      return items.isEmpty() ? "" : items.getFirst();
    }
    return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
  }

  private static String nullToEmpty(final String value) {
    return value == null ? "" : value;
  }

  private boolean enabled() {
    return coparentProperties.enabled() && properties.enabled();
  }
}
