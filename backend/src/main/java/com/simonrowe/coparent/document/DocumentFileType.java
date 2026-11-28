package com.simonrowe.coparent.document;

import java.util.Locale;

/**
 * Decides what a document file is from its own first bytes, never from its name or the
 * {@code Content-Type} the browser claims, so a renamed file cannot pass as something else.
 *
 * <p>WebP is accepted, unlike receipts, because Quick add accepts it and its approved photo
 * becomes the document's file. Everything else, HTML and HEIC included, is refused.
 */
public final class DocumentFileType {

  public static final String JPEG = "image/jpeg";
  public static final String PNG = "image/png";
  public static final String WEBP = "image/webp";
  public static final String PDF = "application/pdf";

  private DocumentFileType() {
  }

  /** The content type a file's leading bytes prove it to be, or null for anything else. */
  public static String sniff(final byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
      return JPEG;
    }
    if (startsWith(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
      return PNG;
    }
    // RIFF <4-byte size> WEBP
    if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
      return WEBP;
    }
    if (startsWith(bytes, 0, '%', 'P', 'D', 'F', '-')) {
      return PDF;
    }
    return null;
  }

  /** Whether the type is one of the images a viewer can show directly. */
  public static boolean isImage(final String mimeType) {
    return JPEG.equals(mimeType) || PNG.equals(mimeType) || WEBP.equals(mimeType);
  }

  /** The extension a downloaded file is named with. */
  public static String extension(final String mimeType) {
    return switch (mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT)) {
      case JPEG -> "jpg";
      case PNG -> "png";
      case WEBP -> "webp";
      default -> "pdf";
    };
  }

  private static boolean startsWith(final byte[] bytes, final int offset, final int... prefix) {
    if (bytes.length < offset + prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if ((bytes[offset + index] & 0xFF) != prefix[index]) {
        return false;
      }
    }
    return true;
  }
}
