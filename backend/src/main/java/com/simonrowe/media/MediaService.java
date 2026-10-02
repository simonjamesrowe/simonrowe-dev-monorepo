package com.simonrowe.media;

import com.simonrowe.common.LogSafe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

  /**
   * What the library stores: images, plus MP4 video and WebVTT captions so a page's demo video
   * is CMS content rather than a file in the frontend bundle.
   */
  static final Set<String> ALLOWED_MIME_TYPES = Set.of(
      "image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml",
      "video/mp4", "text/vtt"
  );

  /**
   * Resized into thumbnail/small/medium/large variants. Everything else is stored as the original
   * only, which the image hydrator already falls back to. WebP is in the second group because the
   * JVM has no WebP reader or writer, so resizing one always failed, and an allowed WebP upload
   * used to return a 500.
   */
  private static final Set<String> RESIZABLE_IMAGE_TYPES = Set.of(
      "image/jpeg", "image/png", "image/gif"
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
      "image/svg+xml", "svg",
      "video/mp4", "mp4",
      "text/vtt", "vtt"
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
    validate(contentType, file.getSize());
    String originalFileName = file.getOriginalFilename();
    if (originalFileName == null) {
      originalFileName = "upload";
    }
    return store(originalFileName, contentType, file.getSize(), file::transferTo, null);
  }

  /**
   * Adds a file to the library from code rather than from an upload, such as a change unit
   * seeding a page's media, so seeded media is ordinary library content: listed, replaceable and
   * backed up with every other upload.
   *
   * <p>Idempotent on {@code legacyId}: when an asset already carries it, that asset is returned
   * and nothing is written, so a re-run never imports a second copy.
   *
   * @param content the file's bytes
   * @param fileName the name to show in the library
   * @param contentType one of {@link #ALLOWED_MIME_TYPES}
   * @param legacyId a stable key for this file, such as {@code seed:portfolio/term-time/hero.webp}
   * @return the stored, or already present, asset
   */
  public MediaAsset importFile(
      final byte[] content,
      final String fileName,
      final String contentType,
      final String legacyId
  ) {
    var existing = repository.findByLegacyId(legacyId);
    if (existing.isPresent()) {
      return existing.get();
    }
    validate(contentType, content.length);
    return store(fileName, contentType, content.length,
        target -> Files.write(target, content), legacyId);
  }

  private static void validate(final String contentType, final long size) {
    if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Unsupported file type. Allowed: JPEG, PNG, GIF, WebP, SVG, MP4 video, WebVTT captions");
    }
    if (size > MAX_FILE_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.valueOf(413), "File too large. Maximum size is 10 MB");
    }
  }

  /** Writes the file to the asset's own directory. */
  @FunctionalInterface
  private interface FileWriter {
    void writeTo(Path target) throws IOException;
  }

  private MediaAsset store(
      final String originalFileName,
      final String contentType,
      final long size,
      final FileWriter writer,
      final String legacyId
  ) {
    String assetId = UUID.randomUUID().toString();
    Path assetDir = Path.of(uploadsPath, assetId);
    try {
      Files.createDirectories(assetDir);

      String extension = getExtension(originalFileName, contentType);
      String storedFileName = "original." + extension;
      Path originalFile = assetDir.resolve(storedFileName);
      writer.writeTo(originalFile);

      Map<String, MediaAsset.VariantInfo> variants = RESIZABLE_IMAGE_TYPES.contains(contentType)
          ? variantGenerator.generateVariants(originalFile, assetId, assetDir.toString())
          : Map.of();

      Instant now = Instant.now();
      MediaAsset asset = new MediaAsset(
          assetId,
          originalFileName,
          contentType,
          size,
          "/uploads/" + assetId + "/" + storedFileName,
          variants,
          now,
          now,
          legacyId
      );

      LOG.info("Stored media asset: id={}, fileName={}",
          assetId, LogSafe.value(originalFileName));
      return repository.save(asset);
    } catch (IOException e) {
      LOG.warn("Failed to store media asset {}: {}", LogSafe.value(originalFileName), e.toString());
      // A half-written asset is a directory nothing points at; it goes with the failure.
      deleteAssetFiles(assetDir);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded file", e);
    }
  }

  /**
   * One page of the library, optionally narrowed to some types and a file name. Both narrow the
   * query rather than the page, so a picker that wants only videos sees every video.
   */
  public Page<MediaAsset> list(
      final String search,
      final Collection<String> mimeTypes,
      final Pageable pageable
  ) {
    boolean bySearch = search != null && !search.isBlank();
    List<String> types = mimeTypes == null ? List.of()
        : mimeTypes.stream().filter(type -> type != null && !type.isBlank()).toList();
    if (bySearch && !types.isEmpty()) {
      return repository.findByFileNameContainingIgnoreCaseAndMimeTypeIn(
          search.strip(), types, pageable);
    }
    if (bySearch) {
      return repository.findByFileNameContainingIgnoreCase(search.strip(), pageable);
    }
    if (!types.isEmpty()) {
      return repository.findByMimeTypeIn(types, pageable);
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

    deleteAssetFiles(Path.of(uploadsPath, id));

    repository.delete(asset);
    LOG.info("Deleted media asset: id={}, fileName={}",
        LogSafe.value(id), LogSafe.value(asset.fileName()));
  }

  private static void deleteAssetFiles(final Path assetDir) {
    if (!Files.exists(assetDir)) {
      return;
    }
    try (var files = Files.walk(assetDir)) {
      files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          LOG.warn("Failed to delete file: {}", path, e);
        }
      });
    } catch (IOException e) {
      LOG.warn("Failed to clean up media files in {}", assetDir, e);
    }
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
