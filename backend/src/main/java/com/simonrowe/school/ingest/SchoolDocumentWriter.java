package com.simonrowe.school.ingest;

import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stores ingested documents, preserving anything a human has already decided about them.
 *
 * <p>The re-ingest path is the risky one. A naive "build a new record and save it" would reset
 * {@code visibility} to the constructor default on every crawl — which is fail-safe for a public
 * item (it silently disappears) but also throws away approvals, and would reset
 * {@code nameGateBlocked}, re-offering blocked content to the approval queue every night until
 * someone clicked the wrong button. Approval state is carried forward explicitly.
 */
@Component
public class SchoolDocumentWriter {

  private final SchoolDocumentRepository repository;

  public SchoolDocumentWriter(final SchoolDocumentRepository repository) {
    this.repository = repository;
  }

  /**
   * Inserts or refreshes a document.
   *
   * @param sourceType where it came from
   * @param sourceRef its URL or message id
   * @param title the title
   * @param body extracted plain text
   * @param publishedAt when the source published it
   * @param yearGroups inferred year groups, a soft hint only
   * @param defaultVisibility the tier to use for a document being seen for the first time.
   *     Website and calendar content is already public; email is not
   * @return the stored document, and whether its text changed
   */
  public WriteResult write(
      final SchoolSourceType sourceType,
      final String sourceRef,
      final String title,
      final String body,
      final Instant publishedAt,
      final List<String> yearGroups,
      final Visibility defaultVisibility) {

    final String id = SchoolIds.documentId(sourceType, sourceRef);
    final String hash = contentHash(body);
    final Optional<SchoolDocument> existing = repository.findById(id);

    if (existing.isPresent() && hash.equals(existing.get().contentHash())) {
      // Unchanged text, but what is said ABOUT the text may still be wrong: the date, the title
      // and the year groups are all derived separately from the body and improve as the
      // derivation does. Website PDFs were stamped with the crawl time for 133 files, and a note
      // re-saved with Year 3 ticked kept the year groups of its first save, because an unchanged
      // document returned here untouched and no later write could ever have corrected it.
      return refreshed(existing.get(), title, publishedAt, yearGroups)
          .map(document -> new WriteResult(document, false, true))
          .orElseGet(() -> new WriteResult(existing.get(), false, false));
    }

    final SchoolDocument document = existing
        .map(prior -> new SchoolDocument(
            id, sourceType, sourceRef, title, body, publishedAt, prior.ingestedAt(),
            // Carry forward every human decision. Only the text and title are refreshed.
            prior.visibility(), prior.proposedVisibility(), prior.proposalReason(),
            prior.approvedBy(), prior.approvedAt(), prior.nameGateBlocked(), yearGroups, hash,
            // Carried forward with every other human decision: a decline survives a re-ingest,
            // or an edited newsletter would silently reappear in the queue.
            prior.declinedAt()))
        .orElseGet(() -> new SchoolDocument(
            id, sourceType, sourceRef, title, body, publishedAt, Instant.now(),
            defaultVisibility, null, null, null, null, false, yearGroups, hash, null));

    return new WriteResult(repository.save(document), true);
  }

  /**
   * Corrects what is stored about a document without touching its text.
   *
   * <p>For a document the caller is not re-reading — a website PDF, which is fetched once and
   * never again — but whose title, year groups or date the caller now knows better.
   *
   * @param sourceType the source kind
   * @param sourceRef the URL or message id
   * @param title the title to record, or null to keep the stored one
   * @param publishedAt the date to record, or null to keep the stored one
   * @param yearGroups the year groups to record, or null to keep the stored ones
   * @return the updated document when anything changed, empty when nothing did or it is unknown
   */
  public Optional<SchoolDocument> refreshMetadata(
      final SchoolSourceType sourceType, final String sourceRef, final String title,
      final Instant publishedAt, final List<String> yearGroups) {
    return repository.findById(SchoolIds.documentId(sourceType, sourceRef))
        .flatMap(stored -> refreshed(stored, title, publishedAt, yearGroups));
  }

  /**
   * Saves a copy carrying the new title, date and year groups, if any of them differ.
   *
   * <p>A null argument means "no opinion" and keeps what is stored. Every human decision is
   * carried forward untouched, because correcting metadata is never a reason to re-open one.
   */
  private Optional<SchoolDocument> refreshed(
      final SchoolDocument stored, final String title, final Instant publishedAt,
      final List<String> yearGroups) {
    final String newTitle = title == null ? stored.title() : title;
    final Instant newDate = publishedAt == null ? stored.publishedAt() : publishedAt;
    final List<String> newYears =
        yearGroups == null ? stored.yearGroups() : List.copyOf(yearGroups);
    if (java.util.Objects.equals(newTitle, stored.title())
        && java.util.Objects.equals(newDate, stored.publishedAt())
        && newYears.equals(stored.yearGroups())) {
      return Optional.empty();
    }
    return Optional.of(repository.save(new SchoolDocument(
        stored.id(), stored.sourceType(), stored.sourceRef(), newTitle, stored.body(), newDate,
        stored.ingestedAt(), stored.visibility(), stored.proposedVisibility(),
        stored.proposalReason(), stored.approvedBy(), stored.approvedAt(),
        stored.nameGateBlocked(), newYears, stored.contentHash(), stored.declinedAt())));
  }

  /**
   * The publication date already stored for a source, if it has been seen before.
   *
   * @param sourceType the source kind
   * @param sourceRef the URL or message id
   * @return the stored publication date, or empty when this is new
   */
  public Optional<java.time.Instant> existingPublishedAt(
      final SchoolSourceType sourceType, final String sourceRef) {
    return repository.findById(SchoolIds.documentId(sourceType, sourceRef))
        .map(SchoolDocument::publishedAt);
  }

  /**
   * Persists a document as-is, used after a classifier proposal or a name-gate veto.
   *
   * @param document the document to store
   * @return the stored document
   */
  public SchoolDocument save(final SchoolDocument document) {
    return repository.save(document);
  }

  /**
   * The outcome of a write.
   *
   * @param document the stored document
   * @param changed whether the text differed from what was already stored. Event extraction is
   *     a model call per document, so this flag is what keeps a nightly crawl of an unchanged
   *     website free
   * @param reindex whether the indexed chunks are stale and need writing again: always when the
   *     text changed, and also when only the title, date or year groups did, because the chunks
   *     carry those as metadata and the assistant reads them from there, not from Mongo
   */
  public record WriteResult(SchoolDocument document, boolean changed, boolean reindex) {

    /**
     * A result whose chunks are stale exactly when its text changed.
     *
     * @param document the stored document
     * @param changed whether the text differed from what was already stored
     */
    public WriteResult(final SchoolDocument document, final boolean changed) {
      this(document, changed, changed);
    }
  }

  /**
   * The hash stored as {@code contentHash}, so a hand edit records the same value a re-ingest of
   * that text would compare against.
   *
   * @param input the document text
   * @return its hex SHA-256
   */
  public static String contentHash(final String input) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
