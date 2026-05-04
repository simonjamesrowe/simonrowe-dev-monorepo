package com.simonrowe.dataops;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HexFormat;
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
  /** Sidecar file in the backups folder tracking which full backup last contained
   * the uploads/ tree, so subsequent backups can dedupe when uploads/ is unchanged. */
  static final String MEDIA_STATE_FILENAME = ".media-state.json";

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
    performBackup(true);
  }

  public void performBackup(final boolean includeMedia) {
    Path tempFile = null;
    try {
      operationsService.updateProgress("Exporting database collections...", 10);
      String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
      String fileName = "backup-" + timestamp + (includeMedia ? "" : "-data") + ".zip";
      tempFile = Files.createTempFile("backup-", ".zip");

      Map<String, Integer> collectionCounts = new LinkedHashMap<>();
      int mediaFileCount = 0;

      // Decide whether to actually bundle uploads/ in this zip. Even when the
      // caller requested a full backup, skip media if uploads/ is byte-identical
      // to the last full backup we shipped — the new manifest will reference
      // that prior backup so restore still works end-to-end.
      String folderId = googleDriveService.findOrCreateFolder();
      String mediaFingerprint = includeMedia ? computeMediaFingerprint() : null;
      MediaState priorState = includeMedia ? readMediaState(folderId) : null;
      boolean reuseMedia = false;
      String mediaSourceName = null;
      if (includeMedia && priorState != null
          && priorState.fingerprint() != null
          && priorState.fingerprint().equals(mediaFingerprint)
          && priorState.sourceBackupName() != null
          && googleDriveService.findFileIdByName(folderId,
              priorState.sourceBackupName()) != null) {
        reuseMedia = true;
        mediaSourceName = priorState.sourceBackupName();
      }
      boolean writeMediaIntoZip = includeMedia && !reuseMedia;

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

        if (writeMediaIntoZip) {
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
        } else if (reuseMedia) {
          operationsService.updateProgress(
              "Skipping media files (unchanged since last full backup)", 60);
          mediaFileCount = priorState != null ? priorState.fileCount() : 0;
          LOG.info("Reusing media from previous backup '{}' "
                  + "(uploads/ unchanged, fingerprint={})",
              mediaSourceName, mediaFingerprint);
        } else {
          operationsService.updateProgress("Skipping media files (data-only backup)", 60);
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
            timestamp, collectionCounts, mediaFileCount, mediaSourceName);
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

      // Update the media-state sidecar only when this backup actually carries
      // fresh media bytes — that's what subsequent incremental backups will
      // reference. If we reused a prior backup's media, the sidecar already
      // points at the right place and we leave it alone.
      if (writeMediaIntoZip && mediaFingerprint != null) {
        try {
          writeMediaState(folderId, new MediaState(
              mediaFingerprint, fileName, mediaFileCount));
        } catch (IOException ex) {
          LOG.warn("Backup uploaded OK but failed to update media-state sidecar: {}",
              ex.getMessage());
        }
      }

      int totalDocs = collectionCounts.values().stream()
          .mapToInt(Integer::intValue).sum();
      String mediaPart = reuseMedia
          ? String.format("%d media files referenced from '%s'",
              mediaFileCount, mediaSourceName)
          : String.format("%d media files", mediaFileCount);
      String summary = String.format(
          "%d collections, %d documents, %s backed up (%s)",
          collectionCounts.size(), totalDocs, mediaPart,
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
      final int mediaFileCount,
      final String mediaSourceName) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"version\": \"1.1\",\n");
    sb.append("  \"createdAt\": \"").append(Instant.now()).append("\",\n");
    sb.append("  \"databaseName\": \"simonrowe\",\n");
    sb.append("  \"collectionCount\": ").append(collectionCounts.size()).append(",\n");
    sb.append("  \"mediaFileCount\": ").append(mediaFileCount).append(",\n");
    if (mediaSourceName != null) {
      sb.append("  \"mediaSource\": \"")
          .append(jsonEscape(mediaSourceName))
          .append("\",\n");
    }
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

  /**
   * Returns a stable hex digest summarising the contents of {@code uploadsPath}
   * by relative path + size + last-modified time, sorted. Two backups produce
   * the same fingerprint iff the set of media files (and their sizes/mtimes)
   * is identical, so we can reuse the previous backup's media bytes.
   */
  String computeMediaFingerprint() {
    Path uploadsDir = Path.of(uploadsPath);
    if (!Files.exists(uploadsDir) || !Files.isDirectory(uploadsDir)) {
      return "empty";
    }
    try (var stream = Files.walk(uploadsDir)) {
      List<String> entries = stream
          .filter(Files::isRegularFile)
          .map(p -> {
            try {
              return uploadsDir.relativize(p) + ":" + Files.size(p)
                  + ":" + Files.getLastModifiedTime(p).toMillis();
            } catch (IOException ex) {
              return uploadsDir.relativize(p) + ":?:?";
            }
          })
          .sorted()
          .toList();
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (String entry : entries) {
        md.update(entry.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (IOException | NoSuchAlgorithmException ex) {
      LOG.warn("Failed to compute media fingerprint, treating as changed: {}",
          ex.getMessage());
      return "error-" + Instant.now().toEpochMilli();
    }
  }

  @org.springframework.lang.Nullable
  private MediaState readMediaState(final String folderId) {
    try {
      byte[] bytes = googleDriveService.readSmallFile(folderId, MEDIA_STATE_FILENAME);
      if (bytes == null) {
        return null;
      }
      String body = new String(bytes, StandardCharsets.UTF_8);
      return new MediaState(
          extractJsonString(body, "fingerprint"),
          extractJsonString(body, "sourceBackupName"),
          (int) extractJsonNumber(body, "fileCount"));
    } catch (IOException ex) {
      LOG.warn("Failed to read media-state sidecar, falling back to full media backup: {}",
          ex.getMessage());
      return null;
    }
  }

  private void writeMediaState(final String folderId, final MediaState state)
      throws IOException {
    String json = "{\n"
        + "  \"version\": \"1.0\",\n"
        + "  \"updatedAt\": \"" + Instant.now() + "\",\n"
        + "  \"fingerprint\": \"" + jsonEscape(state.fingerprint()) + "\",\n"
        + "  \"sourceBackupName\": \""
        + jsonEscape(state.sourceBackupName()) + "\",\n"
        + "  \"fileCount\": " + state.fileCount() + "\n"
        + "}\n";
    googleDriveService.upsertSmallFile(folderId, MEDIA_STATE_FILENAME,
        json.getBytes(StandardCharsets.UTF_8), "application/json");
  }

  private static String jsonEscape(final String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

  private static long extractJsonNumber(final String json, final String key) {
    String marker = "\"" + key + "\"";
    int k = json.indexOf(marker);
    if (k < 0) {
      return 0;
    }
    int colon = json.indexOf(':', k);
    int end = colon + 1;
    while (end < json.length() && (Character.isDigit(json.charAt(end))
        || json.charAt(end) == ' ')) {
      end++;
    }
    String num = json.substring(colon + 1, end).trim();
    try {
      return Long.parseLong(num);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  /** State stored in the .media-state.json sidecar on Drive. */
  record MediaState(String fingerprint, String sourceBackupName, int fileCount) {
  }
}
