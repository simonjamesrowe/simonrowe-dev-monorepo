package com.simonrowe.dataops;

import com.google.api.client.googleapis.media.MediaHttpDownloader;
import com.google.api.client.googleapis.media.MediaHttpDownloaderProgressListener;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class GoogleDriveService {

  private static final Logger LOG = LoggerFactory.getLogger(GoogleDriveService.class);
  static final String FOLDER_NAME = "simonrowe-backups";
  private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
  // 1 MB chunks — small enough that the per-chunk PUT/308 round-trip is
  // dominated by data transfer rather than acks, and progress logs land
  // every chunk so a stalled upload is visible quickly.
  private static final int UPLOAD_CHUNK_SIZE_BYTES = 1024 * 1024;

  @Nullable
  private final Drive drive;
  private final String configuredFolderId;

  public GoogleDriveService(
      @Nullable final Drive drive,
      @Value("${google.drive.folder-id:}") final String configuredFolderId
  ) {
    this.drive = drive;
    this.configuredFolderId = configuredFolderId;
  }

  public boolean isConnected() {
    if (drive == null) {
      return false;
    }
    try {
      drive.files().list().setPageSize(1).setFields("files(id)").execute();
      return true;
    } catch (IOException ex) {
      LOG.warn("Google Drive connection check failed: {}", ex.getMessage());
      return false;
    }
  }

  public String getConnectionError() {
    if (drive == null) {
      return "Google Drive credentials are not configured. "
          + "Set the GOOGLE_DRIVE_CREDENTIALS environment variable.";
    }
    try {
      drive.files().list().setPageSize(1).setFields("files(id)").execute();
      return null;
    } catch (IOException ex) {
      return "Google Drive connection failed: " + ex.getMessage();
    }
  }

  public String findOrCreateFolder() throws IOException {
    checkDrive();
    if (configuredFolderId != null && !configuredFolderId.isBlank()) {
      LOG.debug("Using pre-configured Google Drive folder id={}", configuredFolderId);
      return configuredFolderId;
    }

    FileList result = drive.files().list()
        .setQ("name = '" + FOLDER_NAME + "' and mimeType = '"
            + FOLDER_MIME + "' and trashed = false")
        .setFields("files(id, name)")
        .setPageSize(1)
        .setSupportsAllDrives(true)
        .setIncludeItemsFromAllDrives(true)
        .execute();

    if (result.getFiles() != null && !result.getFiles().isEmpty()) {
      return result.getFiles().get(0).getId();
    }

    File folderMetadata = new File();
    folderMetadata.setName(FOLDER_NAME);
    folderMetadata.setMimeType(FOLDER_MIME);
    File folder = drive.files().create(folderMetadata)
        .setFields("id")
        .setSupportsAllDrives(true)
        .execute();
    LOG.info("Created Google Drive folder '{}' with id={}", FOLDER_NAME, folder.getId());
    return folder.getId();
  }

  public String uploadFile(final String folderId, final String fileName,
      final InputStream inputStream, final long size) throws IOException {
    return uploadFile(folderId, fileName, inputStream, size, null);
  }

  public String uploadFile(final String folderId, final String fileName,
      final InputStream inputStream, final long size,
      @Nullable final BiConsumer<Long, Long> progressListener) throws IOException {
    checkDrive();
    File fileMetadata = new File();
    fileMetadata.setName(fileName);
    fileMetadata.setParents(Collections.singletonList(folderId));

    InputStreamContent content = new InputStreamContent("application/zip", inputStream);
    content.setLength(size);

    Drive.Files.Create request = drive.files().create(fileMetadata, content)
        .setFields("id, name, size, createdTime")
        .setSupportsAllDrives(true);

    MediaHttpUploader uploader = request.getMediaHttpUploader();
    if (uploader != null) {
      uploader.setDirectUploadEnabled(false);
      uploader.setChunkSize(UPLOAD_CHUNK_SIZE_BYTES);
      uploader.setProgressListener(u -> {
        long sent = u.getNumBytesUploaded();
        int percent = size > 0 ? (int) ((sent * 100L) / size) : 0;
        LOG.info("Drive upload '{}' progress: {}/{} bytes ({}%) state={}",
            fileName, sent, size, percent, u.getUploadState());
        if (progressListener != null) {
          progressListener.accept(sent, size);
        }
      });
    }

    File uploaded = request.execute();
    LOG.info("Uploaded backup '{}' to Google Drive (id={}, size={})",
        fileName, uploaded.getId(), size);
    return uploaded.getId();
  }

  public List<BackupMetadata> listBackups(final String folderId) throws IOException {
    checkDrive();
    List<BackupMetadata> backups = new ArrayList<>();
    String pageToken = null;

    do {
      FileList result = drive.files().list()
          .setQ("'" + folderId + "' in parents and trashed = false "
              + "and mimeType = 'application/zip'")
          .setFields("nextPageToken, files(id, name, size, createdTime)")
          .setOrderBy("createdTime desc")
          .setPageSize(100)
          .setPageToken(pageToken)
          .setSupportsAllDrives(true)
          .setIncludeItemsFromAllDrives(true)
          .execute();

      if (result.getFiles() != null) {
        for (File file : result.getFiles()) {
          long fileSize = file.getSize() != null ? file.getSize() : 0;
          Instant createdAt = file.getCreatedTime() != null
              ? Instant.ofEpochMilli(file.getCreatedTime().getValue())
              : Instant.now();
          backups.add(new BackupMetadata(
              file.getId(),
              file.getName(),
              createdAt,
              fileSize,
              BackupMetadata.formatFileSize(fileSize)
          ));
        }
      }
      pageToken = result.getNextPageToken();
    } while (pageToken != null);

    return backups;
  }

  public void downloadFile(final String fileId, final OutputStream outputStream)
      throws IOException {
    checkDrive();
    Drive.Files.Get request = drive.files().get(fileId);
    MediaHttpDownloader downloader = request.getMediaHttpDownloader();
    downloader.setDirectDownloadEnabled(false);
    downloader.setChunkSize(10 * 1024 * 1024); // 10MB chunks
    downloader.setProgressListener(new MediaHttpDownloaderProgressListener() {
      public void progressChanged(MediaHttpDownloader d) {
        LOG.info("Download progress: {} ({} bytes)", 
            d.getDownloadState(), d.getNumBytesDownloaded());
      }
    });
    request.executeMediaAndDownloadTo(outputStream);
  }

  public void deleteFile(final String fileId) throws IOException {
    checkDrive();
    drive.files().delete(fileId).setSupportsAllDrives(true).execute();
  }

  /** Returns the file id of the named file in the folder, or null if missing. */
  @Nullable
  public String findFileIdByName(final String folderId, final String fileName)
      throws IOException {
    checkDrive();
    String escaped = fileName.replace("\\", "\\\\").replace("'", "\\'");
    FileList result = drive.files().list()
        .setQ("'" + folderId + "' in parents and trashed = false and name = '"
            + escaped + "'")
        .setFields("files(id)")
        .setPageSize(1)
        .setSupportsAllDrives(true)
        .setIncludeItemsFromAllDrives(true)
        .execute();
    if (result.getFiles() == null || result.getFiles().isEmpty()) {
      return null;
    }
    return result.getFiles().get(0).getId();
  }

  /**
   * Uploads {@code content} to {@code folderId/fileName}, replacing any existing
   * file with that name. Suitable for small JSON sidecar files; uses direct
   * upload (not the resumable chunked path used for backups).
   */
  public void upsertSmallFile(final String folderId, final String fileName,
      final byte[] content, final String mimeType) throws IOException {
    checkDrive();
    com.google.api.client.http.ByteArrayContent body =
        new com.google.api.client.http.ByteArrayContent(mimeType, content);
    String existingId = findFileIdByName(folderId, fileName);
    if (existingId != null) {
      File metadata = new File();
      metadata.setName(fileName);
      drive.files().update(existingId, metadata, body)
          .setFields("id")
          .setSupportsAllDrives(true)
          .execute();
    } else {
      File metadata = new File();
      metadata.setName(fileName);
      metadata.setParents(Collections.singletonList(folderId));
      metadata.setMimeType(mimeType);
      drive.files().create(metadata, body)
          .setFields("id")
          .setSupportsAllDrives(true)
          .execute();
    }
  }

  /** Reads a small Drive file fully into memory. Returns null if the file is missing. */
  @Nullable
  public byte[] readSmallFile(final String folderId, final String fileName)
      throws IOException {
    String fileId = findFileIdByName(folderId, fileName);
    if (fileId == null) {
      return null;
    }
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    drive.files().get(fileId).executeMediaAndDownloadTo(out);
    return out.toByteArray();
  }

  private void checkDrive() {
    if (drive == null) {
      throw new IllegalStateException(
          "Google Drive is not configured. "
              + "Set the GOOGLE_DRIVE_CREDENTIALS environment variable.");
    }
  }
}
