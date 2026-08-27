package com.simonrowe.dataops;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.simonrowe.migration.changeunits.V020CreateArticleSummaryIndexes;
import com.simonrowe.narration.NarrationRestoreValidator;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;

@Service
public class RestoreService {

  private static final Logger LOG = LoggerFactory.getLogger(RestoreService.class);

  private static final List<String> IMPORT_ORDER_INDEPENDENT = List.of(
      "tags", "skills", "profiles", "social_medias", "tourSteps", "media_assets",
      "content_sources", "aggregated_articles", "aggregated_events",
      // favourites hold no @DBRef, but they point at aggregated_articles and
      // aggregated_events by plain id, so they follow both.
      "favourites",
      // Article summaries are the same shape: no @DBRef, one plain articleId pointing at
      // aggregated_articles, so they follow it here rather than in the ordered list.
      "article_summaries"
  );

  private static final List<String> IMPORT_ORDER_DEPENDENT = List.of(
      "skill_groups", "jobs", "blogs", "code_examples", "narrations"
  );

  private static final String FAVOURITES = "favourites";
  private static final String ARTICLE_SUMMARIES = "article_summaries";
  private static final String FAVOURITES_UNIQUE_INDEX = "idx_type_content";
  private static final String FAVOURITES_LIST_INDEX = "idx_type_created";

  private final MongoTemplate mongoTemplate;
  private final GoogleDriveService googleDriveService;
  private final DataOperationsService operationsService;
  private final BackupService backupService;
  private final com.simonrowe.search.IndexService indexService;
  private final com.simonrowe.embedding.ElasticsearchBackupService esBackupService;
  private final NarrationRestoreValidator narrationRestoreValidator;
  private final String uploadsPath;

  public RestoreService(
      final MongoTemplate mongoTemplate,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService,
      final BackupService backupService,
      final com.simonrowe.search.IndexService indexService,
      final com.simonrowe.embedding.ElasticsearchBackupService esBackupService,
      final NarrationRestoreValidator narrationRestoreValidator,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.mongoTemplate = mongoTemplate;
    this.googleDriveService = googleDriveService;
    this.operationsService = operationsService;
    this.backupService = backupService;
    this.indexService = indexService;
    this.esBackupService = esBackupService;
    this.narrationRestoreValidator = narrationRestoreValidator;
    this.uploadsPath = uploadsPath;
  }

  public void performRestore(final String backupFileId) {
    Path tempZip = null;
    Path localBackup = null;
    try {
      operationsService.updateProgress("Creating safety backup...", 5);
      localBackup = backupService.createLocalBackup();

      operationsService.updateProgress("Downloading backup from Google Drive...", 15);
      tempZip = Files.createTempFile("restore-", ".zip");
      try (var os = new BufferedOutputStream(Files.newOutputStream(tempZip))) {
        googleDriveService.downloadFile(backupFileId, os);
      }

      operationsService.updateProgress("Validating backup archive...", 25);
      validateArchive(tempZip);

      operationsService.updateProgress("Restoring database collections...", 30);
      restoreCollections(tempZip);

      operationsService.updateProgress("Restoring media files...", 70);
      Path mediaZip = resolveMediaSource(tempZip);
      try {
        restoreMediaFiles(mediaZip);
      } finally {
        if (mediaZip != null && !mediaZip.equals(tempZip)) {
          deleteTempFile(mediaZip);
        }
      }

      operationsService.updateProgress("Validating restored narrations...", 78);
      narrationRestoreValidator.reconcile();

      operationsService.updateProgress("Rebuilding search index...", 80);
      indexService.fullSyncSiteIndex();
      indexService.fullSyncBlogIndex();

      operationsService.updateProgress("Restoring vector embeddings...", 90);
      String embeddingsJson = readEntryFromZip(
          tempZip, "embeddings/content-embeddings.json");
      if (embeddingsJson != null) {
        int count = esBackupService.importEmbeddings(embeddingsJson);
        LOG.info("Restored {} vector embeddings from backup", count);
      } else {
        LOG.warn("No vector embeddings found in backup — "
            + "use 'Re-embed Content' from Data Operations to regenerate");
      }

      operationsService.completeOperation(
          "Data restored successfully. Search index and vector embeddings restored.");

    } catch (Exception ex) {
      LOG.error("Restore failed", ex);
      operationsService.failOperation("Restore failed: " + ex.getMessage()
          + ". A safety backup was created before the restore attempt.");
    } finally {
      deleteTempFile(tempZip);
      deleteTempFile(localBackup);
    }
  }

  private void validateArchive(final Path zipFile) throws IOException {
    boolean hasManifest = false;
    boolean hasCollections = false;

    try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if ("manifest.json".equals(entry.getName())) {
          hasManifest = true;
        }
        if (entry.getName().startsWith("collections/")) {
          hasCollections = true;
        }
      }
    }

    if (!hasManifest || !hasCollections) {
      throw new IllegalArgumentException(
          "Invalid backup archive: missing manifest.json or collections directory");
    }
  }

  void restoreCollections(final Path zipFile) throws IOException {
    List<String> allCollections = new ArrayList<>();
    allCollections.addAll(IMPORT_ORDER_INDEPENDENT);
    allCollections.addAll(IMPORT_ORDER_DEPENDENT);

    int progress = 30;
    int progressPerCollection = 35 / allCollections.size();

    for (String collectionName : allCollections) {
      operationsService.updateProgress(
          "Restoring collection: " + collectionName, progress);

      String jsonContent = readEntryFromZip(
          zipFile, "collections/" + collectionName + ".json");
      if (jsonContent == null) {
        if ("narrations".equals(collectionName)) {
          mongoTemplate.dropCollection(collectionName);
        }
        LOG.warn("Collection {} not found in backup, skipping", collectionName);
        progress += progressPerCollection;
        continue;
      }

      mongoTemplate.dropCollection(collectionName);

      List<Document> docs = Document.parse("{\"d\":" + jsonContent + "}")
          .getList("d", Document.class);
      if (docs != null && !docs.isEmpty()) {
        mongoTemplate.insert(docs, collectionName);
        LOG.info("Restored {} documents to collection {}",
            docs.size(), collectionName);
      }

      if (FAVOURITES.equals(collectionName)) {
        ensureFavouriteIndexes();
      }
      if (ARTICLE_SUMMARIES.equals(collectionName)) {
        ensureArticleSummaryIndexes();
      }

      progress += progressPerCollection;
    }
  }

  /**
   * Recreates the favourites indexes after a restore.
   *
   * <p>{@code dropCollection} takes the collection's indexes with it, and
   * these two were created by change units ({@code V013}, {@code V014}) that
   * Mongock has already recorded as executed — so nothing else would ever put
   * them back. Losing the unique index silently re-admits duplicate favourites,
   * which would then surface as an article appearing twice in one digest.
   *
   * <p>Definitions are kept in step with {@code V014MakeFavouritesGlobal};
   * {@code createIndex} is idempotent for an identical specification.
   * Package-private so the round-trip test can exercise it directly.
   */
  void ensureFavouriteIndexes() {
    mongoTemplate.indexOps(FAVOURITES).createIndex(new Index()
        .named(FAVOURITES_UNIQUE_INDEX)
        .on("type", Sort.Direction.ASC)
        .on("contentId", Sort.Direction.ASC)
        .unique());
    mongoTemplate.indexOps(FAVOURITES).createIndex(new Index()
        .named(FAVOURITES_LIST_INDEX)
        .on("type", Sort.Direction.ASC)
        .on("createdAt", Sort.Direction.DESC));
    LOG.info("Recreated favourites indexes after restore");
  }

  /**
   * Recreates the article-summary indexes after a restore, for the same reason
   * {@link #ensureFavouriteIndexes()} exists: {@code dropCollection} takes the
   * collection's indexes with it, and {@code V020} has already been recorded as
   * executed, so Mongock will never put them back.
   *
   * <p>Definitions live in {@code V020CreateArticleSummaryIndexes} and are called from
   * there rather than restated, so the two cannot drift.
   * Package-private so the round-trip test can exercise it directly.
   */
  void ensureArticleSummaryIndexes() {
    V020CreateArticleSummaryIndexes.createIndexes(mongoTemplate);
    LOG.info("Recreated article summary indexes after restore");
  }

  /**
   * If {@code zipFile} contains uploads/ entries it is returned as-is. Otherwise
   * we read the manifest's {@code mediaSource} field, fetch that backup from
   * Drive, and return a temp file pointing at it. Returns the original file if
   * no upgrade is needed; null if no media is available at all.
   */
  private Path resolveMediaSource(final Path zipFile) throws IOException {
    if (zipHasUploads(zipFile)) {
      return zipFile;
    }
    String manifestJson = readEntryFromZip(zipFile, "manifest.json");
    if (manifestJson == null) {
      return zipFile;
    }
    String mediaSource = extractJsonString(manifestJson, "mediaSource");
    if (mediaSource == null || mediaSource.isBlank()) {
      LOG.info("Backup contains no uploads/ and no mediaSource — "
          + "uploads dir will be cleared but no media restored");
      return zipFile;
    }
    LOG.info("Backup references media from prior backup '{}', fetching from Drive",
        mediaSource);
    String folderId = googleDriveService.findOrCreateFolder();
    String sourceFileId = googleDriveService.findFileIdByName(folderId, mediaSource);
    if (sourceFileId == null) {
      throw new IOException("Backup manifest references media source '"
          + mediaSource + "' but that file is not present in Drive backups folder");
    }
    Path sourceZip = Files.createTempFile("restore-media-", ".zip");
    try (var os = new BufferedOutputStream(Files.newOutputStream(sourceZip))) {
      googleDriveService.downloadFile(sourceFileId, os);
    }
    return sourceZip;
  }

  private boolean zipHasUploads(final Path zipFile) throws IOException {
    try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (entry.getName().startsWith("uploads/") && !entry.isDirectory()) {
          return true;
        }
      }
    }
    return false;
  }

  private static String extractJsonString(final String json, final String key) {
    String marker = "\"" + key + "\"";
    int k = json.indexOf(marker);
    if (k < 0) {
      return null;
    }
    int colon = json.indexOf(':', k);
    int q1 = json.indexOf('"', colon + 1);
    int q2 = json.indexOf('"', q1 + 1);
    if (q1 < 0 || q2 < 0) {
      return null;
    }
    return json.substring(q1 + 1, q2);
  }

  private void restoreMediaFiles(final Path zipFile) throws IOException {
    Path uploadsDir = Path.of(uploadsPath);

    if (Files.exists(uploadsDir)) {
      try (Stream<Path> walk = Files.walk(uploadsDir)) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .filter(p -> !p.equals(uploadsDir))
            .forEach(p -> {
              try {
                Files.delete(p);
              } catch (IOException ex) {
                LOG.warn("Failed to delete file during restore cleanup: {}", p, ex);
              }
            });
      }
    }

    Files.createDirectories(uploadsDir);

    try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (entry.getName().startsWith("uploads/") && !entry.isDirectory()) {
          String relativePath = entry.getName().substring("uploads/".length());
          Path targetFile = uploadsDir.resolve(relativePath);
          Files.createDirectories(targetFile.getParent());
          try {
            try (var is = zip.getInputStream(entry)) {
              Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
          } catch (Exception e) {
            LOG.warn("Failed to extract {}, skipping. Error: {}", entry.getName(), e.getMessage());
          }
        }
      }
    }
  }

  private String readEntryFromZip(final Path zipFile, final String entryName)
      throws IOException {
    try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
      var entry = zip.getEntry(entryName);
      if (entry != null) {
        try (var is = zip.getInputStream(entry)) {
          return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    }
    return null;
  }

  private void deleteTempFile(final Path file) {
    if (file != null) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException ex) {
        LOG.warn("Failed to delete temp file: {}", file, ex);
      }
    }
  }
}
