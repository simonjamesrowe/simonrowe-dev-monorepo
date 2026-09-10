package com.simonrowe.migration.changeunits;

import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.retrieval.SchoolVectorStore;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Re-keys email attachment documents off Gmail's attachment id and onto the filename.
 *
 * <p>Attachment documents were keyed on {@code gmail:<messageId>:<attachmentId>}. Gmail's
 * attachment id is an opaque handle minted per {@code messages.get} response rather than a
 * durable identifier, so the same PDF on the same message came back under a different id on a
 * later fetch and the derived document id churned with it. Every consequence was silent: a
 * brand-new document each pass instead of the unchanged one, a paid embedding and a paid
 * classifier call for it, a duplicate row in the approval queue, and the replacement inheriting
 * the parent email's tier — which discards an approval a human had already given. Measured in
 * production on 2026-09-10: fourteen attachments re-ingested on a sync that reported
 * {@code 0 of 43 messages new or changed}, and an approved letter whose citation 404'd because
 * the live document was a restricted duplicate of the approved one.
 *
 * <p>This collapses each set of duplicates onto one document keyed on
 * {@code gmail:<messageId>:<filename>}, which {@code GmailIngestService.attachmentRef} now
 * writes. Three things it is careful about:
 *
 * <ul>
 *   <li><b>The human decision is carried forward, not the newest row.</b> An approval (then a
 *       decline, then recency) picks the survivor, so collapsing duplicates never silently
 *       un-approves something — which is the failure this exists to repair, and would be a
 *       poor thing to reproduce while repairing it.</li>
 *   <li><b>{@code contentHash} is deliberately nulled.</b> The survivor keeps its text, so
 *       leaving the hash intact would make the next sync report it unchanged and never
 *       re-embed it — leaving a document with no chunks, invisible to search, with nothing
 *       reporting a problem. A null hash makes the next sync treat it as changed, and
 *       {@code SchoolDocumentWriter} already carries every human decision across that path.</li>
 *   <li><b>A vector-store failure must not block boot.</b> Mongock runs at startup and a
 *       change-unit exception stops the application. Elasticsearch being unreachable for a
 *       moment is not worth trading a bootable backend for, so the chunk deletion is caught and
 *       logged with the ids it could not clear.</li>
 * </ul>
 */
@ChangeUnit(id = "rekey-gmail-attachment-documents", order = "041", author = "simonrowe")
public class V041RekeyGmailAttachmentDocuments {

  private static final Logger LOG =
      LoggerFactory.getLogger(V041RekeyGmailAttachmentDocuments.class);

  private static final String GMAIL_PREFIX = "gmail:";

  /**
   * Collapses every set of duplicate attachment documents onto one filename-keyed document.
   *
   * @param mongoTemplate the template to read and rewrite documents through
   * @param vectorStore the school index, so an orphaned document's chunks go with it
   */
  @Execution
  public void execution(final MongoTemplate mongoTemplate, final SchoolVectorStore vectorStore) {
    final List<SchoolDocument> attachments = mongoTemplate.find(
        Query.query(Criteria.where("sourceType").is(SchoolSourceType.PDF)
            .and("sourceRef").regex("^" + GMAIL_PREFIX)),
        SchoolDocument.class, V040CreateSchoolCollections.DOCUMENTS);

    final Map<String, List<SchoolDocument>> groups = new LinkedHashMap<>();
    for (SchoolDocument document : attachments) {
      final String canonicalRef = canonicalRef(document);
      if (canonicalRef == null) {
        // No message id or no title to key on. Left exactly as it is: an unparseable row is
        // rarer than a bug in the parser, and deleting one would destroy the only copy.
        LOG.warn("Leaving school attachment {} alone: cannot derive a stable key from {}",
            document.id(), document.sourceRef());
        continue;
      }
      groups.computeIfAbsent(canonicalRef, key -> new ArrayList<>()).add(document);
    }

    int collapsed = 0;
    int removed = 0;
    for (Map.Entry<String, List<SchoolDocument>> group : groups.entrySet()) {
      final String canonicalRef = group.getKey();
      final String canonicalId = SchoolIds.documentId(SchoolSourceType.PDF, canonicalRef);
      final List<SchoolDocument> duplicates = group.getValue();

      // Already migrated: one document, already at the canonical key. Skipped rather than
      // rewritten, so a re-run does not null a live contentHash and force a pointless re-embed
      // of every attachment in the corpus.
      if (duplicates.size() == 1 && canonicalId.equals(duplicates.get(0).id())) {
        continue;
      }

      final SchoolDocument survivor = pickSurvivor(duplicates,
          mongoTemplate.findById(
              canonicalId, SchoolDocument.class, V040CreateSchoolCollections.DOCUMENTS));
      mongoTemplate.save(rekey(survivor, canonicalId, canonicalRef),
          V040CreateSchoolCollections.DOCUMENTS);
      collapsed++;

      for (SchoolDocument stale : duplicates) {
        if (canonicalId.equals(stale.id())) {
          continue;
        }
        clearChunks(vectorStore, stale.id());
        // Dated facts extracted from a duplicate point at it. Repointed rather than deleted:
        // SchoolIds.eventId keys on academic year, date and title rather than on the source,
        // so the next extraction pass collides onto these same rows and refreshes them.
        mongoTemplate.updateMulti(
            Query.query(Criteria.where("sourceDocumentId").is(stale.id())),
            new Update().set("sourceDocumentId", canonicalId),
            V040CreateSchoolCollections.EVENTS);
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(stale.id())),
            V040CreateSchoolCollections.DOCUMENTS);
        removed++;
      }
    }

    LOG.info("Re-keyed {} school attachment documents onto the filename, removing {} duplicates",
        collapsed, removed);
  }

  /**
   * No-op. The duplicates this removes cannot be reconstructed, and the surviving document is
   * the one carrying the human decision — restoring the previous shape would mean re-creating
   * rows keyed on attachment ids that no longer resolve.
   */
  @RollbackExecution
  public void rollback() {
    LOG.info("No rollback for rekey-gmail-attachment-documents: the removed rows were duplicates");
  }

  /**
   * The filename-keyed reference a document should live under.
   *
   * @param document the stored document
   * @return the canonical reference, or null when one cannot be derived
   */
  private String canonicalRef(final SchoolDocument document) {
    final String ref = document.sourceRef();
    if (ref == null || !ref.startsWith(GMAIL_PREFIX)) {
      return null;
    }
    // gmail:<messageId>:<tail>. Split with a limit so a colon in the tail stays in the tail
    // rather than shifting what is read as the message id.
    final String[] parts = ref.split(":", 3);
    if (parts.length < 3 || parts[1].isBlank()) {
      return null;
    }
    final String title = document.title();
    if (title == null || title.isBlank()) {
      return null;
    }
    return GMAIL_PREFIX + parts[1] + ":" + title;
  }

  /**
   * Picks which of a set of duplicates to keep.
   *
   * <p>A human decision outranks everything: an approval first, then a decline, and only then
   * recency. Keeping the newest row instead would discard exactly the approval this migration
   * exists to recover.
   *
   * @param duplicates the documents sharing a canonical key
   * @param existing whatever already sits at the canonical id, or null
   * @return the document to keep
   */
  private SchoolDocument pickSurvivor(
      final List<SchoolDocument> duplicates, final SchoolDocument existing) {
    final List<SchoolDocument> candidates = new ArrayList<>(duplicates);
    if (existing != null) {
      candidates.add(existing);
    }
    return candidates.stream()
        .max(Comparator
            .comparing((SchoolDocument d) -> d.approvedAt() != null)
            .thenComparing(d -> d.declinedAt() != null)
            .thenComparing(d -> latest(d.approvedAt(), d.declinedAt(), d.ingestedAt())))
        .orElseThrow();
  }

  private Instant latest(final Instant... instants) {
    Instant best = Instant.EPOCH;
    for (Instant candidate : instants) {
      if (candidate != null && candidate.isAfter(best)) {
        best = candidate;
      }
    }
    return best;
  }

  /**
   * Rewrites a document under the canonical key, with its content hash cleared so the next sync
   * re-embeds it.
   *
   * @param document the surviving document
   * @param canonicalId the filename-derived id
   * @param canonicalRef the filename-derived source reference
   * @return the document to store
   */
  private SchoolDocument rekey(
      final SchoolDocument document, final String canonicalId, final String canonicalRef) {
    return new SchoolDocument(
        canonicalId,
        document.sourceType(),
        canonicalRef,
        document.title(),
        document.body(),
        document.publishedAt(),
        document.ingestedAt(),
        document.visibility(),
        document.proposedVisibility(),
        document.proposalReason(),
        document.approvedBy(),
        document.approvedAt(),
        document.nameGateBlocked(),
        document.yearGroups(),
        // Null, not the stored hash. See the class comment: an unchanged hash means the next
        // sync never re-embeds this document and it ends up in the index with no chunks.
        null,
        document.declinedAt());
  }

  private void clearChunks(final SchoolVectorStore vectorStore, final String documentId) {
    if (!vectorStore.isEnabled()) {
      return;
    }
    try {
      vectorStore.deleteForDocument(documentId);
    } catch (RuntimeException e) {
      // Logged rather than rethrown: a change-unit failure stops the application from starting,
      // and a leftover chunk is a much smaller problem than a backend that will not boot. The
      // id is named so an operator can clear it by hand.
      LOG.warn("Could not delete chunks for orphaned school attachment {}: {}",
          documentId, e.getMessage());
    }
  }
}
