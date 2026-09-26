package com.simonrowe.coparent.assistant;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Validates transient assistant input before any bytes are sent to the model. */
@Component
public class AssistantInputValidator {

  static final int MAX_TEXT_LENGTH = 20_000;
  static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;

  private static final Map<String, byte[]> SIGNATURES = Map.of(
      "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
      "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
      "image/webp", new byte[] {0x52, 0x49, 0x46, 0x46});

  /** Returns a validated in-memory submission; callers must never persist it. */
  public ValidatedInput validate(final String rawText, final MultipartFile image) {
    final String text = rawText == null ? null : rawText.trim();
    final boolean hasText = text != null && !text.isBlank();
    final boolean hasImage = image != null && !image.isEmpty();
    if (!hasText && !hasImage) {
      throw invalid("Text or an image is required");
    }
    if (hasText && text.length() > MAX_TEXT_LENGTH) {
      throw invalid("Text must be 20,000 characters or fewer");
    }
    if (!hasImage) {
      return new ValidatedInput(text, null, null);
    }
    if (image.getSize() > MAX_IMAGE_BYTES) {
      throw invalid("Image must be 10 MB or smaller");
    }
    final String contentType = image.getContentType();
    if (!SIGNATURES.containsKey(contentType)) {
      throw invalid("Image must be JPEG, PNG, or WebP");
    }
    try {
      final byte[] bytes = image.getBytes();
      if (!hasMagicBytes(contentType, bytes)) {
        throw invalid("Image content does not match its declared format");
      }
      return new ValidatedInput(hasText ? text : null, contentType, bytes);
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image could not be read",
          exception);
    }
  }

  private static boolean hasMagicBytes(final String contentType, final byte[] bytes) {
    final byte[] prefix = SIGNATURES.get(contentType);
    if (bytes.length < prefix.length || !Arrays.equals(prefix,
        Arrays.copyOfRange(bytes, 0, prefix.length))) {
      return false;
    }
    return !"image/webp".equals(contentType)
        || bytes.length >= 12
        && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50;
  }

  private static ResponseStatusException invalid(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  public record ValidatedInput(String text, String imageContentType, byte[] imageBytes) {
    public ValidatedInput {
      imageBytes = imageBytes == null ? null : imageBytes.clone();
    }

    @Override
    public byte[] imageBytes() {
      return imageBytes == null ? null : imageBytes.clone();
    }

    @Override
    public boolean equals(final Object candidate) {
      if (this == candidate) {
        return true;
      }
      if (!(candidate instanceof ValidatedInput other)) {
        return false;
      }
      return Objects.equals(text, other.text)
          && Objects.equals(imageContentType, other.imageContentType)
          && Arrays.equals(imageBytes, other.imageBytes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(text, imageContentType, Arrays.hashCode(imageBytes));
    }

    @Override
    public String toString() {
      return "ValidatedInput[textPresent=" + (text != null)
          + ", imageContentType=" + imageContentType
          + ", imageByteLength=" + (imageBytes == null ? 0 : imageBytes.length) + ']';
    }
  }
}
