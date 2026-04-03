package com.simonrowe.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class MediaSyncService {

  private static final Logger LOG =
      LoggerFactory.getLogger(MediaSyncService.class);

  private static final Map<String, String> EXTENSION_MIME_TYPES = Map.of(
      "jpg", "image/jpeg",
      "jpeg", "image/jpeg",
      "png", "image/png",
      "gif", "image/gif",
      "webp", "image/webp",
      "svg", "image/svg+xml"
  );

  private static final Set<String> SUPPORTED_EXTENSIONS =
      EXTENSION_MIME_TYPES.keySet();

  private final MediaAssetRepository repository;
  private final ImageVariantGenerator imageVariantGenerator;
  private final String uploadsPath;

  public MediaSyncService(
      final MediaAssetRepository repository,
      final ImageVariantGenerator imageVariantGenerator,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.repository = repository;
    this.imageVariantGenerator = imageVariantGenerator;
    this.uploadsPath = uploadsPath;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void syncUploadsDirectory() {
    Path uploadsDir = Path.of(uploadsPath);
    if (!Files.isDirectory(uploadsDir)) {
      LOG.info("Uploads directory does not exist, skipping media sync: {}",
          uploadsDir);
      return;
    }

    LOG.info("Scanning uploads directory for unregistered media files: {}",
        uploadsDir);

    int created = 0;
    int updated = 0;
    int skipped = 0;

    try (Stream<Path> files = Files.list(uploadsDir)) {
      for (Path file : files.toList()) {
        if (!Files.isRegularFile(file)) {
          continue;
        }

        String fileName = file.getFileName().toString();
        String extension = getExtension(fileName);

        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
          skipped++;
          continue;
        }

        try {
          long fileSize = Files.size(file);
          String mimeType = EXTENSION_MIME_TYPES.get(extension);
          Instant fileTime = Files.getLastModifiedTime(file).toInstant();
          MediaAsset existing = repository.findByLegacyId(fileName).orElse(null);
          String assetId = existing != null && existing.id() != null
              ? existing.id()
              : UUID.randomUUID().toString();
          Map<String, MediaAsset.VariantInfo> variants = existing != null
              ? existing.variants()
              : Map.of();

          if (variants == null || variants.isEmpty()) {
            Path variantDir = uploadsDir.resolve(assetId);
            Files.createDirectories(variantDir);
            Map<String, MediaAsset.VariantInfo> generated =
                imageVariantGenerator.generateVariants(
                file, assetId, variantDir.toString());
            variants = generated != null ? generated : Map.of();
          }

          MediaAsset asset = new MediaAsset(
              assetId,
              fileName,
              mimeType,
              fileSize,
              "/uploads/" + fileName,
              variants,
              existing != null ? existing.createdAt() : fileTime,
              existing != null ? Instant.now() : fileTime,
              fileName
          );

          repository.save(asset);
          if (existing == null) {
            created++;
          } else {
            updated++;
          }
        } catch (IOException e) {
          LOG.warn("Failed to read file metadata for {}: {}",
              fileName, e.getMessage());
        }
      }
    } catch (IOException e) {
      LOG.error("Failed to scan uploads directory: {}", e.getMessage());
    }

    LOG.info("Media sync complete: {} assets created, {} updated, {} non-image files skipped.",
        created, updated, skipped);
  }

  private String getExtension(final String fileName) {
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
      return fileName.substring(dotIndex + 1).toLowerCase();
    }
    return "";
  }
}
