package com.simonrowe.media;

import com.simonrowe.common.LogSafe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaService {

  private static final Logger LOG = LoggerFactory.getLogger(MediaService.class);

  private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
      "image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml"
  );

  private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

  /**
   * The extension to store when the uploaded name does not supply a usable one. Keyed by
   * MIME type, which {@link #upload} has already checked against
   * {@link #ALLOWED_MIME_TYPES}, so every value here is trusted.
   */
  private static final Map<String, String> EXTENSION_BY_MIME_TYPE = Map.of(
      "image/jpeg", "jpg",
      "image/png", "png",
      "image/gif", "gif",
      "image/webp", "webp",
      "image/svg+xml", "svg"
  );

  /** An extension we are willing to paste into a path: a short alphanumeric run. */
  private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,5}");

  private final MediaAssetRepository repository;
  private final ImageVariantGenerator variantGenerator;
  private final String uploadsPath;

  public MediaService(
      final MediaAssetRepository repository,
      final ImageVariantGenerator variantGenerator,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.repository = repository;
    this.variantGenerator = variantGenerator;
    this.uploadsPath = uploadsPath;
  }

  public MediaAsset upload(final MultipartFile file) {
    String contentType = file.getContentType();
    if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Unsupported file type. Allowed: JPEG, PNG, GIF, WebP, SVG");
    }

    if (file.getSize() > MAX_FILE_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.valueOf(413), "File too large. Maximum size is 10 MB");
    }

    try {
      String assetId = UUID.randomUUID().toString();
      Path assetDir = Path.of(uploadsPath, assetId);
      Files.createDirectories(assetDir);

      String originalFileName = file.getOriginalFilename();
      if (originalFileName == null) {
        originalFileName = "upload";
      }
      String extension = getExtension(originalFileName, contentType);
      String storedFileName = "original." + extension;
      Path originalFile = assetDir.resolve(storedFileName);
      file.transferTo(originalFile);

      Map<String, MediaAsset.VariantInfo> variants =
          variantGenerator.generateVariants(
              originalFile, assetId, assetDir.toString());

      Instant now = Instant.now();
      MediaAsset asset = new MediaAsset(
          assetId,
          originalFileName,
          contentType,
          file.getSize(),
          "/uploads/" + assetId + "/" + storedFileName,
          variants,
          now,
          now,
          null
      );

      LOG.info("Uploaded media asset: id={}, fileName={}",
          assetId, LogSafe.value(originalFileName));
      return repository.save(asset);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to store uploaded file");
    }
  }

  public Page<MediaAsset> list(
      final String search,
      final String mimeType,
      final Pageable pageable
  ) {
    if (search != null && !search.isBlank() && mimeType != null
        && !mimeType.isBlank()) {
      return repository.findByFileNameContainingIgnoreCaseAndMimeType(
          search, mimeType, pageable);
    }
    if (search != null && !search.isBlank()) {
      return repository.findByFileNameContainingIgnoreCase(
          search, pageable);
    }
    if (mimeType != null && !mimeType.isBlank()) {
      return repository.findByMimeType(mimeType, pageable);
    }
    return repository.findAll(pageable);
  }

  public MediaAsset getById(final String id) {
    return repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Media asset not found"));
  }

  public void delete(final String id) {
    MediaAsset asset = getById(id);

    try {
      Path assetDir = Path.of(uploadsPath, id);
      if (Files.exists(assetDir)) {
        try (var files = Files.walk(assetDir)) {
          files.sorted(java.util.Comparator.reverseOrder())
              .forEach(path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  LOG.warn("Failed to delete file: {}", path, e);
                }
              });
        }
      }
    } catch (IOException e) {
      LOG.warn("Failed to clean up media files for asset: {}", id, e);
    }

    repository.delete(asset);
    LOG.info("Deleted media asset: id={}, fileName={}",
        LogSafe.value(id), LogSafe.value(asset.fileName()));
  }

  /**
   * Derives the extension to store the upload under.
   *
   * <p>The uploaded name is attacker-controlled and the result is pasted into a path, so
   * anything that is not a short alphanumeric run is discarded rather than escaped:
   * {@code getExtension("x.a/../../../../etc/cron.d/evil", ...)} otherwise returns a
   * relative path, and {@code assetDir.resolve(...)} then lands outside the uploads
   * directory entirely. This is the fix for SonarQube {@code javasecurity:S2083}.
   *
   * <p>A legitimate upload keeps the extension it arrived with — the fallback only fires
   * for names with no extension, or with one no browser or file picker would produce.
   *
   * @param fileName the client-supplied file name, never {@code null}
   * @param contentType the MIME type, already validated against {@link #ALLOWED_MIME_TYPES}
   * @return a safe extension, without the leading dot
   */
  private String getExtension(final String fileName, final String contentType) {
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex > 0) {
      String candidate = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
      if (SAFE_EXTENSION.matcher(candidate).matches()) {
        return candidate;
      }
    }
    return EXTENSION_BY_MIME_TYPE.getOrDefault(contentType, "jpg");
  }
}
