package com.simonrowe.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ExternalImageDownloader {

  private static final Logger LOG =
      LoggerFactory.getLogger(ExternalImageDownloader.class);

  private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
      "jpg", "jpeg", "png", "gif", "webp", "avif"
  );

  private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final MediaAssetRepository repository;
  private final ImageVariantGenerator variantGenerator;
  private final String uploadsPath;
  private final HttpClient httpClient;

  public ExternalImageDownloader(
      final MediaAssetRepository repository,
      final ImageVariantGenerator variantGenerator,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath) {
    this.repository = repository;
    this.variantGenerator = variantGenerator;
    this.uploadsPath = uploadsPath;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /**
   * Downloads an image from an external URL, stores it locally with variants,
   * and returns the local path (e.g. /uploads/{assetId}/original.jpg).
   * Returns null if download fails or image is not suitable.
   */
  public String downloadAndStore(final String externalUrl) {
    if (externalUrl == null || externalUrl.isBlank()) {
      return null;
    }

    try {
      String extension = guessExtension(externalUrl);
      if (!ALLOWED_EXTENSIONS.contains(extension)) {
        LOG.debug("Skipping unsupported image format: {}", externalUrl);
        return null;
      }

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(externalUrl))
          .timeout(TIMEOUT)
          .header("User-Agent", "SimonRoweBot/1.0")
          .GET()
          .build();

      HttpResponse<InputStream> response = httpClient.send(
          request, HttpResponse.BodyHandlers.ofInputStream());

      if (response.statusCode() != 200) {
        LOG.debug("Image download returned {}: {}",
            response.statusCode(), externalUrl);
        return null;
      }

      String contentType = response.headers()
          .firstValue("content-type").orElse("");
      if (!contentType.startsWith("image/")) {
        LOG.debug("Not an image content-type: {}", contentType);
        return null;
      }

      String assetId = UUID.randomUUID().toString();
      Path assetDir = Path.of(uploadsPath, assetId);
      Files.createDirectories(assetDir);

      String storedFileName = "original." + extension;
      Path originalFile = assetDir.resolve(storedFileName);

      try (InputStream body = response.body()) {
        long copied = Files.copy(body, originalFile);
        if (copied > MAX_FILE_SIZE) {
          LOG.debug("Image too large ({}), removing: {}", copied, externalUrl);
          deleteDirectory(assetDir);
          return null;
        }
      }

      String originalPath = finalizeAsset(
          assetId, assetDir, originalFile, storedFileName, extension,
          extractFileName(externalUrl));
      LOG.info("Downloaded external image: {} -> {}", externalUrl, originalPath);
      return originalPath;

    } catch (Exception e) {
      LOG.debug("Failed to download image: {}", externalUrl, e);
      return null;
    }
  }

  /**
   * Stores raw image bytes (e.g. a base64-decoded image returned inline by an
   * image-generation model) locally with variants, and returns the local path
   * (e.g. /uploads/{assetId}/original.png). Returns null on any failure.
   */
  public String storeImageBytes(
      final byte[] data,
      final String extension,
      final String sourceName) {
    if (data == null || data.length == 0) {
      return null;
    }
    if (!ALLOWED_EXTENSIONS.contains(extension)) {
      LOG.warn("Refusing to store unsupported image format '{}' for {}",
          extension, sourceName);
      return null;
    }
    if (data.length > MAX_FILE_SIZE) {
      LOG.warn("Generated image too large ({} bytes) for {}, skipping",
          data.length, sourceName);
      return null;
    }

    try {
      String assetId = UUID.randomUUID().toString();
      Path assetDir = Path.of(uploadsPath, assetId);
      Files.createDirectories(assetDir);

      String storedFileName = "original." + extension;
      Path originalFile = assetDir.resolve(storedFileName);
      Files.write(originalFile, data);

      String originalPath = finalizeAsset(
          assetId, assetDir, originalFile, storedFileName, extension, sourceName);
      LOG.info("Stored generated image for '{}' -> {}", sourceName, originalPath);
      return originalPath;

    } catch (Exception e) {
      LOG.warn("Failed to store generated image for '{}': {}",
          sourceName, e.getMessage());
      return null;
    }
  }

  private String finalizeAsset(
      final String assetId,
      final Path assetDir,
      final Path originalFile,
      final String storedFileName,
      final String extension,
      final String sourceName) throws IOException {
    String mimeType = Files.probeContentType(originalFile);
    if (mimeType == null) {
      mimeType = "image/" + extension;
    }

    Map<String, MediaAsset.VariantInfo> variants;
    try {
      variants = variantGenerator.generateVariants(
          originalFile, assetId, assetDir.toString());
    } catch (Exception e) {
      LOG.warn("Variant generation failed for {}, keeping original",
          sourceName, e);
      variants = Map.of();
    }

    long fileSize = Files.size(originalFile);
    String originalPath = "/uploads/" + assetId + "/" + storedFileName;

    Instant now = Instant.now();
    MediaAsset asset = new MediaAsset(
        assetId,
        sourceName,
        mimeType,
        fileSize,
        originalPath,
        variants,
        now,
        now,
        null);

    repository.save(asset);
    return originalPath;
  }

  private String guessExtension(final String url) {
    String path = URI.create(url).getPath();
    int dotIdx = path.lastIndexOf('.');
    if (dotIdx > 0) {
      String ext = path.substring(dotIdx + 1).toLowerCase();
      if (ext.length() <= 5) {
        return ext;
      }
    }
    return "jpg";
  }

  private String extractFileName(final String url) {
    String path = URI.create(url).getPath();
    int slashIdx = path.lastIndexOf('/');
    if (slashIdx >= 0 && slashIdx < path.length() - 1) {
      return path.substring(slashIdx + 1);
    }
    return "external-image.jpg";
  }

  private void deleteDirectory(final Path dir) {
    try (var files = Files.walk(dir)) {
      files.sorted(java.util.Comparator.reverseOrder())
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException e) {
              LOG.warn("Failed to delete: {}", path);
            }
          });
    } catch (IOException e) {
      LOG.warn("Failed to clean up directory: {}", dir);
    }
  }
}
