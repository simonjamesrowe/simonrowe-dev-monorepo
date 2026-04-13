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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class RestoreService {

  private static final Logger LOG = LoggerFactory.getLogger(RestoreService.class);

  private static final List<String> IMPORT_ORDER_INDEPENDENT = List.of(
      "tags", "skills", "profiles", "social_medias", "tourSteps", "media_assets",
      "content_sources", "aggregated_articles", "aggregated_events"
  );
  private static final List<String> IMPORT_ORDER_DEPENDENT = List.of(
      "skill_groups", "jobs", "blogs", "code_examples"
  );

  private final MongoTemplate mongoTemplate;
  private final GoogleDriveService googleDriveService;
  private final DataOperationsService operationsService;
  private final BackupService backupService;
  private final com.simonrowe.search.IndexService indexService;
  private final com.simonrowe.embedding.ElasticsearchBackupService esBackupService;
  private final String uploadsPath;

  public RestoreService(
      final MongoTemplate mongoTemplate,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService,
      final BackupService backupService,
      final com.simonrowe.search.IndexService indexService,
      final com.simonrowe.embedding.ElasticsearchBackupService esBackupService,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.mongoTemplate = mongoTemplate;
    this.googleDriveService = googleDriveService;
    this.operationsService = operationsService;
    this.backupService = backupService;
    this.indexService = indexService;
    this.esBackupService = esBackupService;
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
      restoreMediaFiles(tempZip);

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

    try (var zis = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
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

  private void restoreCollections(final Path zipFile) throws IOException {
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

      progress += progressPerCollection;
    }
  }

  private void restoreMediaFiles(final Path zipFile) throws IOException {
    Path uploadsDir = Path.of(uploadsPath);

    if (Files.exists(uploadsDir)) {
      Files.walk(uploadsDir)
          .sorted(java.util.Comparator.reverseOrder())
          .filter(p -> !p.equals(uploadsDir))
          .forEach(p -> {
            try {
              Files.delete(p);
            } catch (IOException ex) {
              LOG.warn("Failed to delete file during restore cleanup: {}", p, ex);
            }
          });
    }

    Files.createDirectories(uploadsDir);

    try (var zis = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.getName().startsWith("uploads/") && !entry.isDirectory()) {
          String relativePath = entry.getName().substring("uploads/".length());
          Path targetFile = uploadsDir.resolve(relativePath);
          Files.createDirectories(targetFile.getParent());
          Files.copy(zis, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private String readEntryFromZip(final Path zipFile, final String entryName)
      throws IOException {
    try (var zis = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entryName.equals(entry.getName())) {
          return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
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
