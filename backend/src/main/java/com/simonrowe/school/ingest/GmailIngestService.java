package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.admin.SchoolLinkFetcher;
import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.classify.TierClassifier;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.SchoolSyncState;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.model.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ingests school email.
 *
 * <p>Selection is by <b>sender address</b> and nothing else. Not the display name, which a third
 * party already sets to the school's own name, and not a full-text search for the school's name,
 * which also matches a local street and so picks up estate agents.
 *
 * <p>Every message enters at {@link Visibility#RESTRICTED}. The classifier's answer is recorded as
 * a proposal and the name gate can veto it, but nothing here can make anything public.
 */
@Service
public class GmailIngestService {

  private static final Logger LOG = LoggerFactory.getLogger(GmailIngestService.class);
  private static final String SOURCE = "gmail";

  private final SchoolProperties properties;
  private final GmailClient gmail;
  private final TierClassifier classifier;
  private final SchoolDocumentWriter documentWriter;
  private final SchoolSyncStateRepository syncState;
  private final SchoolIngestService ingestService;
  private final SchoolPdfExtractor pdfExtractor;
  private final SchoolEventExtractor eventExtractor;
  private final SchoolEventWriter eventWriter;
  private final SchoolAttachmentStore attachmentStore;
  private final SchoolLinkRepository links;
  private final SchoolLinkFilter linkFilter;
  private final SchoolLinkFetcher linkFetcher;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public GmailIngestService(
      final SchoolProperties properties,
      final GmailClient gmail,
      final TierClassifier classifier,
      final SchoolDocumentWriter documentWriter,
      final SchoolSyncStateRepository syncState,
      final SchoolIngestService ingestService,
      final SchoolPdfExtractor pdfExtractor,
      final SchoolEventExtractor eventExtractor,
      final SchoolEventWriter eventWriter,
      final SchoolAttachmentStore attachmentStore,
      final SchoolLinkRepository links,
      final SchoolLinkFilter linkFilter,
      final SchoolLinkFetcher linkFetcher) {
    this.properties = properties;
    this.gmail = gmail;
    this.classifier = classifier;
    this.documentWriter = documentWriter;
    this.syncState = syncState;
    this.ingestService = ingestService;
    this.pdfExtractor = pdfExtractor;
    this.eventExtractor = eventExtractor;
    this.eventWriter = eventWriter;
    this.attachmentStore = attachmentStore;
    this.links = links;
    this.linkFilter = linkFilter;
    this.linkFetcher = linkFetcher;
  }

  /**
   * Runs a mail sync.
   *
   * @return how many messages produced new or changed content
   */
  public int sync() {
    if (!gmail.isConfigured()) {
      LOG.debug("No Gmail credential configured; skipping mail ingest");
      return 0;
    }

    final String query = buildQuery();
    final List<String> ids;
    try {
      ids = gmail.listMessageIds(query);
    } catch (GmailClient.GmailAuthException e) {
      // The shape a revoked credential takes. Recorded where an operator will find it rather
      // than logged and forgotten: Google revokes Gmail-scoped refresh tokens on a password
      // change with no other signal, and the symptom is silence.
      recordFailure("Gmail authentication failed: " + e.getMessage());
      return 0;
    }

    LOG.info("Gmail query matched {} messages", ids.size());
    int changed = 0;
    for (String id : ids) {
      try {
        if (ingestOne(id)) {
          changed++;
        }
      } catch (GmailClient.GmailAuthException e) {
        recordFailure("Gmail authentication failed mid-sync: " + e.getMessage());
        return changed;
      }
    }
    recordSuccess();
    LOG.info("Gmail ingest complete: {} of {} messages new or changed", changed, ids.size());
    return changed;
  }

  private boolean ingestOne(final String messageId) throws GmailClient.GmailAuthException {
    final Optional<GmailMessage> fetched = gmail.fetchMessage(messageId);
    if (fetched.isEmpty()) {
      return false;
    }
    final GmailMessage message = fetched.get();

    // Belt and braces: the Gmail query already filters by sender, but the query language is a
    // remote system's interpretation of a string and this check is ours.
    if (!properties.allowsSender(message.fromAddress())) {
      LOG.debug("Skipping {} from {} (display name {})",
          messageId, message.fromAddress(), message.fromDisplayName());
      return false;
    }
    if (message.body().isBlank()) {
      return false;
    }

    final SchoolDocumentWriter.WriteResult result = documentWriter.write(
        SchoolSourceType.EMAIL,
        messageId,
        message.subject(),
        message.body(),
        message.receivedAt(),
        List.of(),
        Visibility.RESTRICTED);

    // Before the changed() check, deliberately. Recording a link costs no network call and is
    // idempotent, and the link table is separate state that may not exist yet for a message
    // ingested before this feature — gating it on the body having changed means those messages
    // never surrender their links at all.
    recordLinks(message, result.document());

    // Also before the changed() check, and for a related reason: the attachment bytes are
    // state of their own, held outside Mongo, and an unchanged email must still be able to
    // put them back. It costs nothing in the ordinary case — see the guard inside.
    ingestAttachments(message, result.document(), result.changed());

    if (!result.changed()) {
      return false;
    }

    SchoolDocument document = result.document();

    // Only classify what a human has not already ruled on. Re-proposing a document that was
    // approved or declined last week would churn the queue and could quietly reverse a decision.
    //
    // There is no name check here any more, on the owner's explicit instruction: personal names,
    // pupils' included, are no longer suppressed anywhere in Term Time. Approval is now the only
    // control over what reaches the public tier, which is what it was always doing the real work
    // of anyway — the gate blocked 98 of 99 broadcasts and could not tell a catering company
    // from a child.
    if (document.approvedAt() == null) {
      final Visibility proposed = classifier.propose(document.title(), document.body());
      document = documentWriter.save(
          document.withProposal(proposed, "classifier proposed " + proposed));
    }

    ingestService.embed(document);

    // Dated facts. The calendar feed carries 17 events for a whole academic year; the rest of
    // school life is announced in these emails, so without this "what is on this week" is
    // answered from almost nothing.
    for (SchoolEvent event : eventExtractor.extract(document)) {
      eventWriter.write(event);
    }

    return true;
  }

  /**
   * Records the hyperlinks in a message without following any of them.
   *
   * <p>An email can link anywhere, including to things the sender never meant to share and to
   * addresses that exist only to register that someone clicked. Following them during ingest
   * would turn a school-mailbox reader into a general crawler pointed at whatever arrives. They
   * are stored as candidates and wait for a person.
   *
   * <p>Existing rows are left untouched, so a decision already made is never re-offered when the
   * message is re-ingested.
   */
  private void recordLinks(final GmailMessage message, final SchoolDocument document) {
    for (GmailMessage.Link link : message.links()) {
      // Footer boilerplate never becomes a row. See SchoolLinkFilter: a queue that repeats the
      // same four footer links for every message is a queue nobody reads.
      if (!linkFilter.isWorthOffering(link.url())) {
        continue;
      }
      final String id = SchoolIds.documentId(SchoolSourceType.EMAIL,
          document.id() + '|' + link.url());
      if (links.existsById(id)) {
        continue;
      }
      links.save(new SchoolLink(id, document.id(), link.url(),
          link.text() == null || link.text().isBlank() ? link.url() : link.text(),
          Instant.now(), SchoolLink.Status.PENDING, null, null));

      // The one link the ingester follows on its own. See SchoolLinkFilter.isAutoFetchable: a
      // newsletter on the school's own site is already being read wholesale by the website
      // crawl, so following it reaches nobody new. The row is written first and then fetched,
      // so a failure leaves an ordinary pending link a human can retry rather than losing it.
      if (linkFilter.isAutoFetchable(link.url())) {
        autoFetch(id, link.url());
      }
    }
  }

  /**
   * Follows a school newsletter link during ingest.
   *
   * <p>Never throws: a newsletter that 404s, times out or comes back as something unreadable
   * must not fail the ingest of the email that mentioned it.
   *
   * @param linkId the stored link's id
   * @param url the address, for the log line only
   */
  private void autoFetch(final String linkId, final String url) {
    try {
      linkFetcher.fetch(linkId);
      LOG.info("Auto-fetched school newsletter {}", url);
    } catch (RuntimeException e) {
      LOG.warn("Could not auto-fetch {}: {}", url, e.getMessage());
    }
  }

  /**
   * Downloads PDF attachments and ingests their text as documents in their own right.
   *
   * <p>The weekly newsletter frequently IS the attachment — the message body is a covering
   * sentence and everything useful is in the PDF. Recording the filename and discarding the
   * bytes, which is what this did before, left the assistant with the covering sentence.
   *
   * <p>Each attachment becomes a separate {@code PDF} document rather than being appended to the
   * email's text: it gets its own tier decision, its own chunks and its own citation, and a
   * timetable is a different kind of thing from the note that carried it.
   *
   * <p>Runs even when the parent email is unchanged, because the stored bytes are state of their
   * own and can go missing while the document, its chunks and its citation all survive — the
   * assistant then goes on offering a link that 404s, with nothing anywhere reporting it. That
   * is not hypothetical: in production the store had no volume behind it, so every recreate of
   * the backend emptied it. The guard below is what keeps the ordinary case free: with the file
   * already on disk and the email unchanged there is no download, no text extraction and no
   * write at all.
   *
   * @param message the message being ingested
   * @param parent the email document the attachments belong to
   * @param parentChanged whether the email's own text differed from what was already stored
   */
  private void ingestAttachments(final GmailMessage message, final SchoolDocument parent,
      final boolean parentChanged) throws GmailClient.GmailAuthException {
    for (GmailMessage.Attachment attachment : message.attachments()) {
      if (!attachment.looksLikePdf()) {
        continue;
      }
      final String sourceRef = "gmail:" + message.id() + ":" + attachment.attachmentId();
      // Derivable without downloading anything, which is the point: it lets an untouched
      // attachment be recognised for the price of one `stat`.
      final String pdfId = SchoolIds.documentId(SchoolSourceType.PDF, sourceRef);
      if (!parentChanged && attachmentStore.has(pdfId)) {
        continue;
      }
      final byte[] bytes = gmail.fetchAttachment(message.id(), attachment.attachmentId())
          .orElse(null);
      if (bytes == null) {
        continue;
      }
      final String text = pdfExtractor.extractTextFromBytes(bytes);
      if (text == null || text.isBlank()) {
        LOG.debug("Attachment {} produced no text", attachment.filename());
        continue;
      }

      // Inherits the parent email's tier, and RESTRICTED is that tier by construction. An
      // attachment must never be more visible than the message that carried it.
      final SchoolDocumentWriter.WriteResult result = documentWriter.write(
          SchoolSourceType.PDF,
          sourceRef,
          attachment.filename(),
          text,
          message.receivedAt(),
          List.of(),
          parent.visibility());

      SchoolDocument pdf = result.document();
      // Keep the original so an answer can link to it rather than only paraphrasing. Served
      // by SchoolAttachmentController, which refuses anything not in the public tier.
      //
      // Written before the unchanged check, so reaching here with the bytes in hand always
      // leaves the store repaired. Gating this on the text having changed — which is what it
      // did — is what made a lost file permanent.
      attachmentStore.store(pdf.id(), bytes);

      if (!result.changed()) {
        continue;
      }
      if (pdf.approvedAt() == null) {
        pdf = documentWriter.save(
            pdf.withProposal(classifier.propose(pdf.title(), pdf.body()), "classifier proposal"));
      }
      ingestService.embed(pdf);
      for (SchoolEvent event : eventExtractor.extract(pdf)) {
        eventWriter.write(event);
      }
      LOG.info("Ingested attachment {} ({} chars)", attachment.filename(), text.length());
    }
  }

  /**
   * Builds the Gmail search expression.
   *
   * <p>Sender-scoped, plus an optional date floor. Deliberately not a keyword search: the school's
   * name is also a local street name.
   *
   * @return the query string
   */
  String buildQuery() {
    final String senders = properties.senderAllowlist().stream()
        .map(s -> "from:" + s)
        .reduce((a, b) -> a + " OR " + b)
        .orElse("");
    final StringBuilder query = new StringBuilder("(" + senders + ")");
    if (properties.ingestFromDate() != null) {
      query.append(" after:")
          .append(properties.ingestFromDate().toString().replace('-', '/'));
    }
    return query.toString();
  }

  private void recordSuccess() {
    final SchoolSyncState state =
        syncState.findById(SOURCE).orElseGet(() -> SchoolSyncState.initial(SOURCE));
    syncState.save(new SchoolSyncState(SOURCE, state.cursor(), Instant.now(),
        state.lastFailureAt(), state.lastFailureReason(), state.pageEtags()));
  }

  private void recordFailure(final String reason) {
    final SchoolSyncState state =
        syncState.findById(SOURCE).orElseGet(() -> SchoolSyncState.initial(SOURCE));
    syncState.save(new SchoolSyncState(SOURCE, state.cursor(), state.lastSuccessAt(),
        Instant.now(), reason, state.pageEtags()));
    LOG.error("Gmail ingest failed: {}", reason);
  }
}
