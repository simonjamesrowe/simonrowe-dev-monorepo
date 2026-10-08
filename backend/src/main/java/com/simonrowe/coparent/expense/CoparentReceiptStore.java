package com.simonrowe.coparent.expense;

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
 * Keeps the bytes of expense receipts on disk, one file per receipt.
 *
 * <p><b>Deliberately not under {@code uploads/}.</b> That path is served by a resource handler
 * with no authorisation at all, so a receipt there would be public to anyone who guessed its
 * name. These are reachable only through {@code ExpenseReceiptController}, after a family
 * membership check, and they live on their own volume in production.
 *
 * <p>Files are named {@code <familyId>/<receiptId>}, both ObjectId hex. Nothing a parent chose,
 * such as the original filename, ever becomes part of a path.
 */
@Component
public class CoparentReceiptStore {

  private static final Logger LOG = LoggerFactory.getLogger(CoparentReceiptStore.class);

  private final Path root;

  public CoparentReceiptStore(
      // Relative to the backend's working directory locally; absolute and on a named volume in
      // production, or every deploy would delete the files from the container's writable layer.
      @Value("${coparent.receipt-path:coparent-receipts/}") final String path) {
    this.root = Path.of(path).toAbsolutePath().normalize();
  }

  /** Writes a receipt's bytes. Failures are thrown: an upload must not report success. */
  public void store(final ObjectId familyId, final ObjectId receiptId, final byte[] bytes) {
    final Path file = pathFor(familyId, receiptId);
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, bytes);
    } catch (IOException exception) {
      throw new UncheckedIOException("Could not store receipt " + receiptId, exception);
    }
  }

  /** Reads a receipt's bytes, or empty when the file is missing. */
  public Optional<byte[]> read(final ObjectId familyId, final ObjectId receiptId) {
    final Path file = pathFor(familyId, receiptId);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException exception) {
      LOG.warn("Could not read receipt {}: {}", receiptId, exception.getMessage());
      return Optional.empty();
    }
  }

  /** Deletes a receipt's bytes. Best-effort: a leftover file is invisible without its record. */
  public void delete(final ObjectId familyId, final ObjectId receiptId) {
    try {
      Files.deleteIfExists(pathFor(familyId, receiptId));
    } catch (IOException exception) {
      LOG.warn("Could not delete receipt {}: {}", receiptId, exception.getMessage());
    }
  }

  /** The directory backups read from and restores write to. */
  public Path root() {
    return root;
  }

  private Path pathFor(final ObjectId familyId, final ObjectId receiptId) {
    // Both segments are ObjectId hex today; re-checked so a future caller cannot escape.
    final Path resolved = root.resolve(familyId.toHexString()).resolve(receiptId.toHexString())
        .normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Receipt path escapes the store");
    }
    return resolved;
  }
}
