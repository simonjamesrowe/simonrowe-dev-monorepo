package com.simonrowe.coparent.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps the bytes of family documents on disk, one file per page photo or PDF.
 *
 * <p><b>Deliberately not under {@code uploads/}.</b> That path is served by a resource handler
 * with no authorisation at all, so a passport photo there would be public to anyone who guessed
 * its name. These are reachable only through {@code DocumentController}, after a family
 * membership check, and they live on their own volume in production.
 *
 * <p>Files are named {@code <familyId>/<fileId>}, both ObjectId hex. Nothing a parent chose,
 * such as the original filename, ever becomes part of a path. The app never deletes a file,
 * not even when its document is archived: a real deletion is done by hand.
 */
@Component
public class CoparentDocumentStore {

  private static final Logger LOG = LoggerFactory.getLogger(CoparentDocumentStore.class);

  private final Path root;

  public CoparentDocumentStore(
      // Relative to the backend's working directory locally; absolute and on a named volume in
      // production, or every deploy would delete the files from the container's writable layer.
      @Value("${coparent.document-path:coparent-documents/}") final String path) {
    this.root = Path.of(path).toAbsolutePath().normalize();
  }

  /** Writes a file's bytes. Failures are thrown: an upload must not report success. */
  public void store(final ObjectId familyId, final ObjectId fileId, final byte[] bytes) {
    final Path file = pathFor(familyId, fileId);
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, bytes);
    } catch (IOException exception) {
      throw new UncheckedIOException("Could not store document file " + fileId, exception);
    }
  }

  /** Reads a file's bytes, or empty when the file is missing. */
  public Optional<byte[]> read(final ObjectId familyId, final ObjectId fileId) {
    final Path file = pathFor(familyId, fileId);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException exception) {
      LOG.warn("Could not read document file {}: {}", fileId, exception.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Removes a file written moments ago whose document was then never saved. The only deletion
   * this store performs: a file with no record is unreachable, so this is tidying, never loss.
   */
  void discard(final ObjectId familyId, final ObjectId fileId) {
    try {
      Files.deleteIfExists(pathFor(familyId, fileId));
    } catch (IOException exception) {
      LOG.warn("Could not discard document file {}: {}", fileId, exception.getMessage());
    }
  }

  /** The directory backups read from and restores write to. */
  public Path root() {
    return root;
  }

  private Path pathFor(final ObjectId familyId, final ObjectId fileId) {
    // Both segments are ObjectId hex today; re-checked so a future caller cannot escape.
    final Path resolved = root.resolve(familyId.toHexString()).resolve(fileId.toHexString())
        .normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Document path escapes the store");
    }
    return resolved;
  }
}
