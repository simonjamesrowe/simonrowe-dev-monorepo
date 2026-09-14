package com.simonrowe.resume;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supplies the headshot bytes the CV renders in its sidebar.
 *
 * <p>Resolution order is CMS override, then the bundled default. The bundled default
 * exists so the feature ships working: uploading to the Media Library needs a human
 * browser login, and a CV that silently renders photo-less until somebody remembers a
 * manual step is a worse outcome than a photo that needs a deploy to change.
 */
@Component
public class ResumePhotoProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ResumePhotoProvider.class);

  private static final String BUNDLED_PHOTO = "/resume/headshot.jpg";
  private static final String UPLOADS_PREFIX = "/uploads/";

  private final Path uploadsRoot;

  /** Read once and reused; the bundled photo cannot change without a restart. */
  private volatile byte[] bundledPhoto;
  private volatile boolean bundledPhotoLoaded;

  public ResumePhotoProvider(
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.uploadsRoot = Path.of(uploadsPath).toAbsolutePath().normalize();
  }

  /**
   * Resolves the headshot for a CV render.
   *
   * @param photoUrl the CMS-selected image URL, or null when none has been chosen
   * @return the image bytes, or empty when neither an override nor the bundled default
   *     can be read
   */
  public Optional<byte[]> resolve(final String photoUrl) {
    return readOverride(photoUrl).or(this::readBundled);
  }

  private Optional<byte[]> readOverride(final String photoUrl) {
    if (photoUrl == null || photoUrl.isBlank()) {
      return Optional.empty();
    }
    if (!photoUrl.startsWith(UPLOADS_PREFIX)) {
      LOG.warn("Ignoring CV photo outside the uploads directory: {}", photoUrl);
      return Optional.empty();
    }

    String relative = photoUrl.substring(UPLOADS_PREFIX.length());
    Path file;
    try {
      file = uploadsRoot.resolve(relative).normalize();
    } catch (InvalidPathException e) {
      LOG.warn("Ignoring CV photo with an unusable path: {}", photoUrl);
      return Optional.empty();
    }

    // The URL is CMS data. Normalising alone does not stop `../` escaping the uploads
    // root, so the resolved path is checked for containment before anything is read.
    if (!file.startsWith(uploadsRoot)) {
      LOG.warn("Ignoring CV photo that escapes the uploads directory: {}", photoUrl);
      return Optional.empty();
    }

    try {
      if (!Files.isRegularFile(file)) {
        LOG.warn("CV photo is configured but missing on disk: {}", photoUrl);
        return Optional.empty();
      }
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException e) {
      LOG.warn("Failed to read CV photo {}: {}", photoUrl, e.getMessage());
      return Optional.empty();
    }
  }

  private Optional<byte[]> readBundled() {
    if (!bundledPhotoLoaded) {
      synchronized (this) {
        if (!bundledPhotoLoaded) {
          bundledPhoto = loadBundled();
          bundledPhotoLoaded = true;
        }
      }
    }
    return Optional.ofNullable(bundledPhoto);
  }

  private byte[] loadBundled() {
    try (InputStream in = ResumePhotoProvider.class.getResourceAsStream(BUNDLED_PHOTO)) {
      if (in == null) {
        LOG.warn("Bundled CV headshot {} is not on the classpath", BUNDLED_PHOTO);
        return null;
      }
      return in.readAllBytes();
    } catch (IOException e) {
      LOG.warn("Failed to read bundled CV headshot: {}", e.getMessage());
      return null;
    }
  }
}
