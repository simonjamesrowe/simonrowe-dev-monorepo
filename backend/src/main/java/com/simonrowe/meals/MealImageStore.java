package com.simonrowe.meals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Recipe photos, kept outside {@code uploads/} because that path is served without
 * authorisation and these photos come from copyrighted books.
 *
 * <p>Files are named by the SHA-256 of their bytes plus an extension from the sniffed type, so
 * storing the same photo twice is a no-op and a name can be cached for ever. The type is read
 * from the bytes, never from a file name.
 */
@Component
public class MealImageStore {

  static final Pattern NAME = Pattern.compile("[0-9a-f]{64}\\.(jpg|png|webp)");
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

  private final Path root;

  public MealImageStore(@Value("${meals.image-path:meal-images/}") final String path) {
    this.root = Path.of(path).toAbsolutePath().normalize();
  }

  /**
   * Stores an image and returns its file name.
   *
   * @param bytes the image
   * @return the stored name, or empty when the bytes are not a JPEG, PNG or WebP image
   */
  public Optional<String> store(final byte[] bytes) {
    String extension = sniff(bytes);
    if (extension == null) {
      return Optional.empty();
    }
    String name = sha256(bytes) + "." + extension;
    Path target = resolve(name);
    try {
      Files.createDirectories(root);
      if (!Files.exists(target)) {
        Path temp = Files.createTempFile(root, "upload-", ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not store meal image", e);
    }
    return Optional.of(name);
  }

  /**
   * Reads a stored image.
   *
   * @param name a name returned by {@link #store}
   * @return the bytes, or empty for an unknown or malformed name
   */
  public Optional<byte[]> read(final String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      return Optional.empty();
    }
    Path file = resolve(name);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read meal image", e);
    }
  }

  /** The directory images live in; the backup exports it whole. */
  public Path root() {
    return root;
  }

  static String contentType(final String name) {
    if (name.endsWith(".png")) {
      return "image/png";
    }
    return name.endsWith(".webp") ? "image/webp" : "image/jpeg";
  }

  static String sniff(final byte[] bytes) {
    if (bytes == null || bytes.length < 12) {
      return null;
    }
    if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
      return "jpg";
    }
    boolean png = true;
    for (int i = 0; i < PNG.length; i++) {
      png &= bytes[i] == PNG[i];
    }
    if (png) {
      return "png";
    }
    boolean riff = bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F';
    boolean webp = bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    return riff && webp ? "webp" : null;
  }

  private Path resolve(final String name) {
    Path file = root.resolve(name).normalize();
    if (!file.startsWith(root)) {
      throw new IllegalArgumentException("Image name escapes the image directory");
    }
    return file;
  }

  private static String sha256(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
