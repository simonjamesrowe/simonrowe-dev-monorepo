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
import java.util.stream.Stream;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
      "content_sources", "favourites", "narrations",
      // Generated article summaries cost an LLM call each, so a backup that skipped them
      // would silently discard paid-for content on the next restore.
      "article_summaries",
      // Release notes on /status are the same: one LLM call per release, and the backfill
      // of history is only baked into the image that produced it — a restore into a newer
      // image could not regenerate the older entries at all.
      "platform_releases",
      // Share slugs are already pasted into other people's Slack channels and LinkedIn
      // posts. A restore that dropped them would break URLs that exist in the wild, and
      // re-minting would produce different values — nothing recreates a lost slug.
      "short_links",
      // Term Time. school_documents retains the original text of restricted items so the
      // classifier can be changed without re-reading the mailbox — and Gmail guarantees no
      // retention window for its incremental cursor, so that re-read may simply not be
      // available. Losing this collection is not recoverable by re-ingesting.
      "school_documents", "school_events", "school_sync_state",
      // Spend history. Not reconstructable: the provider bills in aggregate and
      // these rows are the only per-call record that exists.
      "school_usage",
      // Fetch/ignore decisions on links found in email. Losing these re-offers every
      // link already declined, which is the one outcome that makes the queue useless.
      "school_links"
  );
  private static final Set<String> COPARENT_BACKUP_COLLECTIONS = Set.of(
      "families", "parents", "children", "invitations", "onboardingstates", "events",
      "eventcategories", "schedulechangerequests", "conversations", "audits"
  );

  private void exportIndex(final ZipOutputStream zos, final String index) {
    try {
      String embeddingsJson = esBackupService.exportEmbeddings(index);
      zos.putNextEntry(new ZipEntry("embeddings/" + index + ".json"));
      zos.write(embeddingsJson.getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    } catch (Exception ex) {
      LOG.warn("Failed to export embeddings for index {}, skipping: {}", index, ex.getMessage());
    }
  }

  private final MongoClient mongoClient;
  private final String databaseName;
  private final String coparentDatabaseName;
  private final GoogleDriveService googleDriveService;
  private final DataOperationsService operationsService;
  private final com.simonrowe.embedding.ElasticsearchBackupService esBackupService;
  private final String uploadsPath;
  private final String schoolAttachmentPath;

  public BackupService(
      final MongoClient mongoClient,
      final MongoTemplate mongoTemplate,
      @Qualifier("coparentMongoTemplate") final MongoTemplate coparentMongoTemplate,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService,
      final com.simonrowe.embedding.ElasticsearchBackupService esBackupService,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath,
      @Value("${school.attachment-path:school-attachments/}") final String schoolAttachmentPath
  ) {
    this.mongoClient = mongoClient;
    this.databaseName = mongoTemplate.getDb().getName();
    this.coparentDatabaseName = coparentMongoTemplate.getDb().getName();
    this.googleDriveService = googleDriveService;
    this.operationsService = operationsService;
    this.esBackupService = esBackupService;
    this.uploadsPath = uploadsPath;
    this.schoolAttachmentPath = schoolAttachmentPath;
  }

  /**
   * Runs a full, self-contained backup (all collections + media + embeddings)
   * and uploads it to Google Drive.
   *
   * @return {@code true} if the backup completed and uploaded successfully
   */
  public boolean performBackup() {
    Path tempFile = null;
    try {
      operationsService.updateProgress("Exporting database collections...", 10);
      String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
      String fileName = "backup-" + timestamp + ".zip";
      tempFile = Files.createTempFile("backup-", ".zip");

      Map<String, Integer> collectionCounts = new LinkedHashMap<>();
      int mediaFileCount = 0;
      int narrationAudioFileCount = 0;

      String folderId = googleDriveService.findOrCreateFolder();

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

        for (String collectionName : COPARENT_BACKUP_COLLECTIONS) {
          exportCollection(zos, coparentDatabaseName, collectionName,
              "databases/coparent/collections/" + collectionName + ".json",
              "coparent." + collectionName, collectionCounts);
        }

        operationsService.updateProgress("Adding media files...", 60);
        Path uploadsDir = Path.of(uploadsPath);
        if (Files.exists(uploadsDir) && Files.isDirectory(uploadsDir)) {
          List<Path> mediaFiles;
          try (Stream<Path> walk = Files.walk(uploadsDir)) {
            mediaFiles = walk.filter(Files::isRegularFile).toList();
          }
          mediaFileCount = mediaFiles.size();
          narrationAudioFileCount = (int) mediaFiles.stream()
              .filter(path -> uploadsDir.relativize(path).startsWith("narrations"))
              .count();
          for (Path mediaFile : mediaFiles) {
            String entryPath = "uploads/" + uploadsDir.relativize(mediaFile);
            zos.putNextEntry(new ZipEntry(entryPath));
            Files.copy(mediaFile, zos);
            zos.closeEntry();
          }
        }

        // Term Time's PDF attachments. Not recoverable by re-ingesting: Gmail guarantees no
        // retention window for its incremental cursor, and a message can be deleted from the
        // mailbox entirely. The extracted text lives in Mongo, but the original does not.
        operationsService.updateProgress("Adding school attachments...", 68);
        Path attachmentsDir = Path.of(schoolAttachmentPath);
        if (Files.exists(attachmentsDir) && Files.isDirectory(attachmentsDir)) {
          try (Stream<Path> walk = Files.walk(attachmentsDir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
              zos.putNextEntry(
                  new ZipEntry("school-attachments/" + attachmentsDir.relativize(file)));
              Files.copy(file, zos);
              zos.closeEntry();
            }
          }
        }

        operationsService.updateProgress("Exporting vector embeddings...", 70);
        // Two indexes, each its own entry. The entry name has always carried the index name,
        // so an archive written before Term Time existed simply lacks the second file and
        // restores fine — no manifest version bump needed.
        exportIndex(zos, esBackupService.contentIndexName());
        exportIndex(zos, esBackupService.schoolIndexName());

        operationsService.updateProgress("Writing manifest...", 75);
        String manifest = buildManifest(timestamp, collectionCounts, mediaFileCount,
            narrationAudioFileCount);
        zos.putNextEntry(new ZipEntry("manifest.json"));
        zos.write(manifest.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      operationsService.updateProgress("Uploading to Google Drive...", 80);
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
          "%d collections, %d documents, %d media files backed up; "
              + "%d narrations, %d narration audio files and %d article summaries (%s)",
          collectionCounts.size(), totalDocs, mediaFileCount,
          collectionCounts.getOrDefault("narrations", 0), narrationAudioFileCount,
          collectionCounts.getOrDefault("article_summaries", 0),
          BackupMetadata.formatFileSize(fileSize));
      operationsService.completeOperation(summary);
      return true;

    } catch (Exception ex) {
      LOG.error("Backup failed", ex);
      operationsService.failOperation("Backup failed: " + ex.getMessage());
      return false;
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
        exportCollection(zos, databaseName, collectionName,
            "collections/" + collectionName + ".json", collectionName, null);
      }
      for (String collectionName : COPARENT_BACKUP_COLLECTIONS) {
        exportCollection(zos, coparentDatabaseName, collectionName,
            "databases/coparent/collections/" + collectionName + ".json",
            "coparent." + collectionName, null);
      }

      Path uploadsDir = Path.of(uploadsPath);
      if (Files.exists(uploadsDir) && Files.isDirectory(uploadsDir)) {
        List<Path> mediaFiles;
        try (Stream<Path> walk = Files.walk(uploadsDir)) {
          mediaFiles = walk.filter(Files::isRegularFile).toList();
        }
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

  private void exportCollection(
      final ZipOutputStream zos,
      final String sourceDatabase,
      final String collectionName,
      final String entryName,
      final String countKey,
      final Map<String, Integer> collectionCounts) throws IOException {
    final MongoCollection<RawBsonDocument> collection = mongoClient.getDatabase(sourceDatabase)
        .getCollection(collectionName, RawBsonDocument.class);
    final List<RawBsonDocument> docs = collection.find().into(new ArrayList<>());
    if (collectionCounts != null) {
      collectionCounts.put(countKey, docs.size());
    }
    final StringBuilder json = new StringBuilder("[\n");
    for (int index = 0; index < docs.size(); index++) {
      if (index > 0) {
        json.append(",\n");
      }
      json.append(docs.get(index).toJson(JSON_SETTINGS));
    }
    json.append("\n]");
    zos.putNextEntry(new ZipEntry(entryName));
    zos.write(json.toString().getBytes(StandardCharsets.UTF_8));
    zos.closeEntry();
  }

  private String buildManifest(final String timestamp,
      final Map<String, Integer> collectionCounts,
      final int mediaFileCount,
      final int narrationAudioFileCount) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"version\": \"1.1\",\n");
    sb.append("  \"createdAt\": \"").append(Instant.now()).append("\",\n");
    sb.append("  \"databaseName\": \"").append(databaseName).append("\",\n");
    sb.append("  \"coparentDatabaseName\": \"")
        .append(coparentDatabaseName).append("\",\n");
    sb.append("  \"collectionCount\": ").append(collectionCounts.size()).append(",\n");
    sb.append("  \"mediaFileCount\": ").append(mediaFileCount).append(",\n");
    sb.append("  \"narrationCount\": ")
        .append(collectionCounts.getOrDefault("narrations", 0)).append(",\n");
    sb.append("  \"narrationAudioFileCount\": ")
        .append(narrationAudioFileCount).append(",\n");
    sb.append("  \"articleSummaryCount\": ")
        .append(collectionCounts.getOrDefault("article_summaries", 0)).append(",\n");
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
