package com.simonrowe.coparent.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/** Tiny synthetic files with the right leading bytes. Never a real document. */
final class DocumentTestFiles {

  private DocumentTestFiles() {
  }

  static byte[] jpeg() {
    final byte[] bytes = new byte[64];
    Arrays.fill(bytes, (byte) 0x11);
    bytes[0] = (byte) 0xFF;
    bytes[1] = (byte) 0xD8;
    bytes[2] = (byte) 0xFF;
    return bytes;
  }

  static byte[] png() {
    final byte[] bytes = new byte[32];
    final int[] magic = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    for (int index = 0; index < magic.length; index++) {
      bytes[index] = (byte) magic[index];
    }
    return bytes;
  }

  static byte[] webp() {
    final byte[] bytes = new byte[32];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
    System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
    return bytes;
  }

  static byte[] fakePdf() {
    return "%PDF-1.7\n% synthetic\n".getBytes(StandardCharsets.US_ASCII);
  }

  static byte[] html() {
    return "<!doctype html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
  }

  /** A real, blank PDF with the given number of pages, for rendering. */
  static byte[] pdf(final int pages) {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (int index = 0; index < pages; index++) {
        document.addPage(new PDPage());
      }
      document.save(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
