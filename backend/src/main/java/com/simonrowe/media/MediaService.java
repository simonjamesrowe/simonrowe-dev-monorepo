package com.simonrowe.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
      String extension = getExtension(originalFileName);
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
          assetId + "/" + storedFileName,
          variants,
          now,
          now,
          null
      );

      LOG.info("Uploaded media asset: id={}, fileName={}",
          assetId, originalFileName);
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
        id, asset.fileName());
  }

  private String getExtension(final String fileName) {
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex > 0) {
      return fileName.substring(dotIndex + 1).toLowerCase();
    }
    return "jpg";
  }
}
