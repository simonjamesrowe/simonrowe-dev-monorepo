package com.simonrowe.dataops;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.RawBsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class BackupService {

  private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final JsonWriterSettings JSON_SETTINGS =
      JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).indent(true).build();
  private static final Set<String> BACKUP_COLLECTIONS = Set.of(
      "blogs", "tags", "skills", "skill_groups", "jobs",
      "profiles", "social_medias", "tourSteps", "media_assets",
      "code_examples", "aggregated_articles", "aggregated_events",
      "content_sources"
  );

  private final MongoClient mongoClient;
  private final String databaseName;
  private final GoogleDriveService googleDriveService;
  private final DataOperationsService operationsService;
  private final com.simonrowe.embedding.ElasticsearchBackupService esBackupService;
  private final String uploadsPath;

  public BackupService(
      final MongoClient mongoClient,
      final MongoTemplate mongoTemplate,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService,
      final com.simonrowe.embedding.ElasticsearchBackupService esBackupService,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.mongoClient = mongoClient;
    this.databaseName = mongoTemplate.getDb().getName();
    this.googleDriveService = googleDriveService;
    this.operationsService = operationsService;
    this.esBackupService = esBackupService;
    this.uploadsPath = uploadsPath;
  }

  public void performBackup() {
    Path tempFile = null;
    try {
      operationsService.updateProgress("Exporting database collections...", 10);
      String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
      String fileName = "backup-" + timestamp + ".zip";
      tempFile = Files.createTempFile("backup-", ".zip");

      Map<String, Integer> collectionCounts = new LinkedHashMap<>();
      int mediaFileCount = 0;

      try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(tempFile));
           ZipOutputStream zos = new ZipOutputStream(fos)) {

        int progress = 10;
        int progressPerCollection = 50 / BACKUP_COLLECTIONS.size();

        for (String collectionName : BACKUP_COLLECTIONS) {
          operationsService.updateProgress(
              "Exporting collection: " + collectionName, progress);
          MongoDatabase db = mongoClient.getDatabase(databaseName);
          MongoCollection<RawBsonDocument> collection =
              db.getCollection(collectionName, RawBsonDocument.class);
          List<RawBsonDocument> docs = collection.find().into(new ArrayList<>());
          collectionCounts.put(collectionName, docs.size());

          StringBuilder sb = new StringBuilder();
          sb.append("[\n");
          for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
              sb.append(",\n");
            }
            sb.append(docs.get(i).toJson(JSON_SETTINGS));
          }
          sb.append("\n]");

          zos.putNextEntry(new ZipEntry("collections/" + collectionName + ".json"));
          zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
          zos.closeEntry();

          progress += progressPerCollection;
        }

        operationsService.updateProgress("Adding media files...", 60);
        Path uploadsDir = Path.of(uploadsPath);
        if (Files.exists(uploadsDir) && Files.isDirectory(uploadsDir)) {
          List<Path> mediaFiles = Files.walk(uploadsDir)
              .filter(Files::isRegularFile)
              .toList();
          mediaFileCount = mediaFiles.size();
          for (Path mediaFile : mediaFiles) {
            String entryPath = "uploads/" + uploadsDir.relativize(mediaFile);
            zos.putNextEntry(new ZipEntry(entryPath));
            Files.copy(mediaFile, zos);
            zos.closeEntry();
          }
        }

        operationsService.updateProgress("Exporting vector embeddings...", 70);
        try {
          String embeddingsJson = esBackupService.exportEmbeddings();
          zos.putNextEntry(new ZipEntry("embeddings/content-embeddings.json"));
          zos.write(embeddingsJson.getBytes(StandardCharsets.UTF_8));
          zos.closeEntry();
        } catch (Exception ex) {
          LOG.warn("Failed to export embeddings, skipping: {}", ex.getMessage());
        }

        operationsService.updateProgress("Writing manifest...", 75);
        String manifest = buildManifest(
            timestamp, collectionCounts, mediaFileCount);
        zos.putNextEntry(new ZipEntry("manifest.json"));
        zos.write(manifest.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      operationsService.updateProgress("Uploading to Google Drive...", 80);
      String folderId = googleDriveService.findOrCreateFolder();
      long fileSize = Files.size(tempFile);
      try (var is = Files.newInputStream(tempFile)) {
        googleDriveService.uploadFile(folderId, fileName, is, fileSize,
            (sent, total) -> {
              int percent = total > 0 ? 80 + (int) ((sent * 15L) / total) : 80;
              if (percent > 95) {
                percent = 95;
              }
              operationsService.updateProgress(
                  String.format("Uploading to Google Drive... %d%% (%s / %s)",
                      total > 0 ? (int) ((sent * 100L) / total) : 0,
                      BackupMetadata.formatFileSize(sent),
                      BackupMetadata.formatFileSize(total)),
                  percent);
            });
      }

      int totalDocs = collectionCounts.values().stream()
          .mapToInt(Integer::intValue).sum();
      String summary = String.format(
          "%d collections, %d documents, %d media files backed up (%s)",
          collectionCounts.size(), totalDocs, mediaFileCount,
          BackupMetadata.formatFileSize(fileSize));
      operationsService.completeOperation(summary);

    } catch (Exception ex) {
      LOG.error("Backup failed", ex);
      operationsService.failOperation("Backup failed: " + ex.getMessage());
    } finally {
      deleteTempFile(tempFile);
    }
  }

  public Path createLocalBackup() throws IOException {
    String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
    Path tempFile = Files.createTempFile("pre-restore-backup-", ".zip");

    try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(tempFile));
         ZipOutputStream zos = new ZipOutputStream(fos)) {

      for (String collectionName : BACKUP_COLLECTIONS) {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<RawBsonDocument> collection =
            db.getCollection(collectionName, RawBsonDocument.class);
        List<RawBsonDocument> docs = collection.find().into(new ArrayList<>());
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < docs.size(); i++) {
          if (i > 0) {
            sb.append(",\n");
          }
          sb.append(docs.get(i).toJson(JSON_SETTINGS));
        }
        sb.append("\n]");
        zos.putNextEntry(new ZipEntry("collections/" + collectionName + ".json"));
        zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      Path uploadsDir = Path.of(uploadsPath);
      if (Files.exists(uploadsDir) && Files.isDirectory(uploadsDir)) {
        List<Path> mediaFiles = Files.walk(uploadsDir)
            .filter(Files::isRegularFile)
            .toList();
        for (Path mediaFile : mediaFiles) {
          String entryPath = "uploads/" + uploadsDir.relativize(mediaFile);
          zos.putNextEntry(new ZipEntry(entryPath));
          Files.copy(mediaFile, zos);
          zos.closeEntry();
        }
      }
    }

    LOG.info("Created local pre-restore backup at {}", tempFile);
    return tempFile;
  }

  private String buildManifest(final String timestamp,
      final Map<String, Integer> collectionCounts,
      final int mediaFileCount) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"version\": \"1.0\",\n");
    sb.append("  \"createdAt\": \"").append(Instant.now()).append("\",\n");
    sb.append("  \"databaseName\": \"simonrowe\",\n");
    sb.append("  \"collectionCount\": ").append(collectionCounts.size()).append(",\n");
    sb.append("  \"mediaFileCount\": ").append(mediaFileCount).append(",\n");
    sb.append("  \"collections\": {\n");
    int i = 0;
    for (Map.Entry<String, Integer> entry : collectionCounts.entrySet()) {
      sb.append("    \"").append(entry.getKey()).append("\": ").append(entry.getValue());
      if (i < collectionCounts.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
      i++;
    }
    sb.append("  }\n");
    sb.append("}\n");
    return sb.toString();
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
