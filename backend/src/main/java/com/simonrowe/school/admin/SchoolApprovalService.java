package com.simonrowe.school.admin;

import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The only path by which content becomes public.
 *
 * <p>Three things have to happen together on approval, and missing any one of them produces a
 * quiet inconsistency rather than an error:
 *
 * <ol>
 *   <li>the document's tier changes;
 *   <li>every event extracted from it changes with it — otherwise a public document's dates stay
 *       invisible to anonymous visitors, or worse, a revoked document's dates stay visible;
 *   <li>the vector chunks are rewritten, because {@code visibility} is chunk metadata and the
 *       retrieval filter reads the copy in Elasticsearch, not the one in Mongo.
 * </ol>
 *
 * <p>Point three is the one that bites. Approving without re-embedding leaves the document public
 * in Mongo and restricted in the index, so the approval appears to do nothing at all.
 */
@Service
public class SchoolApprovalService {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolApprovalService.class);

  private final SchoolDocumentRepository documents;
  private final SchoolEventRepository events;
  private final SchoolIngestService ingestService;

  public SchoolApprovalService(
      final SchoolDocumentRepository documents,
      final SchoolEventRepository events,
      final SchoolIngestService ingestService) {
    this.documents = documents;
    this.events = events;
    this.ingestService = ingestService;
  }

  /**
   * Documents proposed for promotion and awaiting a decision.
   *
   * @return the queue, newest publication first
   */
  public List<SchoolDocument> queue() {
    return documents.findAwaitingApproval().stream()
        .sorted(java.util.Comparator.comparing(
            SchoolDocument::publishedAt, java.util.Comparator.reverseOrder()))
        .toList();
  }

  /**
   * Promotes a document to public.
   *
   * <p>Re-runs the name gate rather than trusting the flag stored at ingest. The staff directory
   * changes when the crawler runs, so a document classified when the directory was stale — or
   * empty, which blocks everything — deserves a fresh answer at the moment of the decision. This
   * is also the last check before content becomes irreversibly public.
   *
   * @param id the document id
   * @param approver who is approving
   * @return the updated document, or empty if it does not exist
   */
  public Optional<SchoolDocument> approve(final String id, final String approver) {
    return approve(id, approver, false);
  }

  /**
   * Promotes a document to the public tier.
   *
   * <p>There is no longer a name check standing between an operator and this decision. Term Time
   * used to re-run a staff-directory heuristic here and refuse anything naming a person it could
   * not place; that was removed on the owner's explicit instruction, because it blocked 98 of 99
   * school broadcasts and could not distinguish the catering company from a child. Approval by a
   * human who has read the text is the control now, and it is the whole control.
   *
   * <p>{@code force} is retained only so the existing two-argument endpoint keeps its shape; it
   * no longer overrides anything, because there is nothing left to override.
   *
   * @param id the document id
   * @param approver who is approving
   * @param force retained for call-site compatibility; has no effect
   * @return the updated document, or empty if it does not exist
   */
  public Optional<SchoolDocument> approve(
      final String id, final String approver, final boolean force) {
    return documents.findById(id).map(document -> {
      final SchoolDocument approved = document.withApproval(approver, Instant.now(), true);
      LOG.info("Document {} approved for the public tier by {}", id, approver);
      return persist(approved);
    });
  }

  /**
   * Declines a proposal without blocking the document forever.
   *
   * <p>Distinct from the name gate: declining clears the proposal so it leaves the queue, but a
   * later re-ingest can propose it again. A gate block is permanent until the content changes.
   *
   * @param id the document id
   * @return the updated document, or empty if it does not exist
   */
  public Optional<SchoolDocument> decline(final String id) {
    return documents.findById(id)
        .map(document -> persist(document.withDeclined(Instant.now())));
  }

  /**
   * Takes a document back out of the public tier.
   *
   * @param id the document id
   * @return the updated document, or empty if it does not exist
   */
  public Optional<SchoolDocument> revoke(final String id) {
    return documents.findById(id).map(document -> {
      LOG.info("Public access to document {} revoked", id);
      return persist(document.withApprovalRevoked());
    });
  }

  private SchoolDocument persist(final SchoolDocument document) {
    final SchoolDocument saved = documents.save(document);
    cascadeToEvents(saved);
    // Rewrite the chunks so the index agrees with Mongo about the tier. Without this the
    // approval is invisible to retrieval and appears to have done nothing.
    ingestService.embed(saved);
    return saved;
  }

  private void cascadeToEvents(final SchoolDocument document) {
    final List<SchoolEvent> extracted = events.findAll().stream()
        .filter(e -> document.id().equals(e.sourceDocumentId()))
        .filter(e -> e.visibility() != document.visibility())
        .toList();
    for (SchoolEvent event : extracted) {
      events.save(new SchoolEvent(event.id(), event.title(), event.startDate(), event.endDate(),
          event.allDay(), event.eventType(), event.yearGroups(), event.academicYear(),
          event.sourceType(), event.sourceDocumentId(), document.visibility(),
          event.description(), event.location(), event.time(), event.sourceUrl()));
    }
    if (!extracted.isEmpty()) {
      LOG.info("Cascaded tier {} to {} events from document {}",
          document.visibility(), extracted.size(), document.id());
    }
  }
}
