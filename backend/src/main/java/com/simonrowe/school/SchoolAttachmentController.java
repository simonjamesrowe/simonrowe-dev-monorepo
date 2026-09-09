package com.simonrowe.school;

import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.Visibility;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves school PDFs that arrived as email attachments.
 *
 * <p>Every request is checked against the document's tier. Only {@link Visibility#PUBLIC}
 * documents are served, because this endpoint is reachable without authentication — the page it
 * serves is. A restricted attachment returns <b>404, not 403</b>: a 403 would confirm that a
 * document with that id exists, which is itself a disclosure when the id is derived from a
 * message.
 *
 * <p>This is the reason attachments are not written into {@code uploads/}, which is served
 * unauthenticated by a resource handler with no check of any kind.
 */
@RestController
@RequestMapping("/api/school/attachments")
public class SchoolAttachmentController {

  private final SchoolDocumentRepository documents;
  private final SchoolAttachmentStore store;
  private final SchoolProperties properties;

  public SchoolAttachmentController(
      final SchoolDocumentRepository documents,
      final SchoolAttachmentStore store,
      final SchoolProperties properties) {
    this.documents = documents;
    this.store = store;
    this.properties = properties;
  }

  /**
   * Serves one attachment, if it is public.
   *
   * @param id the owning document's id
   * @return the PDF, or 404
   */
  @GetMapping("/{id}")
  public ResponseEntity<byte[]> attachment(@PathVariable final String id) {
    if (!properties.enabled()) {
      return ResponseEntity.notFound().build();
    }
    final SchoolDocument document = documents.findById(id).orElse(null);
    if (document == null || document.visibility() != Visibility.PUBLIC) {
      return ResponseEntity.notFound().build();
    }
    return store.read(id)
        .map(bytes -> ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            // inline so a click opens it rather than downloading a hex-named file, but with a
            // readable filename for anyone who does save it.
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + safeFilename(document.title()) + "\"")
            .body(bytes))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static String safeFilename(final String title) {
    final String base = title == null || title.isBlank() ? "school-document" : title;
    final String cleaned = base.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
    final String withoutExtension = cleaned.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
        ? cleaned.substring(0, cleaned.length() - 4)
        : cleaned;
    return (withoutExtension.isBlank() ? "school-document" : withoutExtension) + ".pdf";
  }
}
