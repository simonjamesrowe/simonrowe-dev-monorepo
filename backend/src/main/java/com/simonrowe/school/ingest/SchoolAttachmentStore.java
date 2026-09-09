package com.simonrowe.school.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps the bytes of PDFs that arrived as email attachments, so an answer can link to the
 * original rather than only paraphrasing it.
 *
 * <p><b>Deliberately not under the {@code uploads/} directory.</b> That path is served straight
 * out by a {@code ResourceHandlerRegistry} mapping with no authorisation at all, so putting
 * school attachments there would publish every one of them — including the restricted ones —
 * and quietly defeat the entire tiering design. These live in their own directory and are
 * reachable only through a controller that checks the document's tier first.
 *
 * <p>Files are named by document id, which is a SHA-256 hex string. That is deliberate as well:
 * it means nothing a sender chose ever becomes a path, so a malicious filename cannot traverse
 * out of the directory.
 */
@Component
public class SchoolAttachmentStore {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolAttachmentStore.class);

  private final Path root;

  public SchoolAttachmentStore(
      // Relative to the backend's working directory, which start-backend.sh sets to backend/.
      // A "backend/" prefix here produces backend/backend/school-attachments — the files land,
      // nothing errors, and they are simply not where anyone looks for them.
      @Value("${school.attachment-path:school-attachments/}") final String path) {
    this.root = Path.of(path).toAbsolutePath();
  }

  /**
   * Stores the bytes for a document.
   *
   * @param documentId the owning document's id, used as the filename
   * @param bytes the file contents
   * @return true when stored
   */
  public boolean store(final String documentId, final byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return false;
    }
    try {
      Files.createDirectories(root);
      Files.write(pathFor(documentId), bytes);
      return true;
    } catch (IOException e) {
      LOG.warn("Could not store attachment for {}: {}", documentId, e.getMessage());
      return false;
    }
  }

  /**
   * Reads back the bytes for a document.
   *
   * @param documentId the owning document's id
   * @return the contents, or empty when nothing is stored
   */
  public Optional<byte[]> read(final String documentId) {
    final Path file = pathFor(documentId);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException e) {
      LOG.warn("Could not read attachment for {}: {}", documentId, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Whether a document has a stored file.
   *
   * @param documentId the owning document's id
   * @return true when a file exists
   */
  public boolean has(final String documentId) {
    return Files.isRegularFile(pathFor(documentId));
  }

  private Path pathFor(final String documentId) {
    // Resolved against the root and then re-checked: documentId is always a hex digest today,
    // but a future caller passing something else must not be able to escape the directory.
    final Path resolved = root.resolve(documentId + ".pdf").normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Attachment id escapes the store: " + documentId);
    }
    return resolved;
  }
}
