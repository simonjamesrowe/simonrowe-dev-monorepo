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
    final String hash = sha256(body);
    final Optional<SchoolDocument> existing = repository.findById(id);

    if (existing.isPresent() && hash.equals(existing.get().contentHash())) {
      // Unchanged text, but the date may still be wrong: it is derived separately from the body
      // and improves as the derivation does. Website PDFs were stamped with the crawl time for
      // 133 files, and because an unchanged document returned here untouched, no later crawl
      // could ever have corrected them. Corrected in place and deliberately still reported as
      // UNCHANGED — the chunks are identical, so re-embedding would be pure cost.
      final SchoolDocument stored = existing.get();
      if (publishedAt != null && !publishedAt.equals(stored.publishedAt())) {
        return new WriteResult(repository.save(stored.withPublishedAt(publishedAt)), false);
      }
      return new WriteResult(stored, false);
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
   * @param changed whether the text differed from what was already stored, and so whether the
   *     chunk needs re-embedding. Embedding is the only paid step in ingestion, so this flag is
   *     what keeps a nightly crawl of an unchanged website free
   */
  public record WriteResult(SchoolDocument document, boolean changed) {
  }

  private static String sha256(final String input) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
