package com.simonrowe.coparent.document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checks the files of one upload before anything is written: 1 to 12 files, each a JPEG, PNG,
 * WebP or PDF by its bytes, at most 20 MB each and 60 MB together.
 *
 * <p>The 60 MB request cap is what keeps 12 x 20 MB out: one request may not hold twelve
 * full-size files. Spring's multipart limit and the CoParent nginx location are set just above
 * it (61 MB and 62 MB) so that this check, with its readable message, is the one a parent sees.
 */
public final class DocumentUploads {

  public static final int MAX_FILES = 12;
  public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
  public static final long MAX_REQUEST_BYTES = 60L * 1024 * 1024;

  private DocumentUploads() {
  }

  /**
   * One checked file. Equality reads the bytes and {@code toString} gives only their length, so
   * a passport photo never reaches a log line.
   *
   * @param mimeType the type its bytes proved it to be
   * @param bytes the file
   */
  public record Upload(String mimeType, byte[] bytes) {

    @Override
    public boolean equals(final Object other) {
      return other instanceof Upload that
          && Objects.equals(mimeType, that.mimeType)
          && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hashCode(mimeType) + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
      return "Upload[mimeType=%s, bytes=<%d bytes>]"
          .formatted(mimeType, bytes == null ? 0 : bytes.length);
    }
  }

  /** Reads and checks multipart files. */
  public static List<Upload> fromMultipart(final List<MultipartFile> files) {
    final List<byte[]> contents = new ArrayList<>();
    if (files != null) {
      for (final MultipartFile file : files) {
        if (file == null) {
          continue;
        }
        if (file.getSize() > MAX_FILE_BYTES) {
          throw badRequest("Each file can be up to 20 MB");
        }
        try {
          contents.add(file.getBytes());
        } catch (IOException exception) {
          throw badRequest("A file could not be read");
        }
      }
    }
    return check(contents);
  }

  /**
   * Re-checks uploads at the service boundary, so a caller other than the controller (Quick
   * add's approval, for one) cannot skip the limits or bring its own idea of the type.
   */
  public static List<Upload> checkUploads(final List<Upload> uploads) {
    return check(uploads == null ? List.of()
        : uploads.stream().map(upload -> upload == null ? null : upload.bytes()).toList());
  }

  /** Checks files already in memory. */
  public static List<Upload> check(final List<byte[]> contents) {
    if (contents == null || contents.isEmpty()) {
      throw badRequest("Add at least one file");
    }
    if (contents.size() > MAX_FILES) {
      throw badRequest("A document can have up to " + MAX_FILES + " files");
    }
    long total = 0;
    final List<Upload> uploads = new ArrayList<>(contents.size());
    for (final byte[] bytes : contents) {
      if (bytes == null || bytes.length == 0) {
        throw badRequest("A file is empty");
      }
      if (bytes.length > MAX_FILE_BYTES) {
        throw badRequest("Each file can be up to 20 MB");
      }
      total += bytes.length;
      final String type = DocumentFileType.sniff(bytes);
      if (type == null) {
        throw badRequest("Files must be photos (JPEG, PNG or WebP) or PDFs");
      }
      uploads.add(new Upload(type, bytes));
    }
    if (total > MAX_REQUEST_BYTES) {
      throw badRequest("The files together can be up to 60 MB. Add the rest as a new version.");
    }
    return List.copyOf(uploads);
  }

  private static ResponseStatusException badRequest(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
