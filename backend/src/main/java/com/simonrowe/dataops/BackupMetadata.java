package com.simonrowe.dataops;

import java.time.Instant;

public record BackupMetadata(
    String fileId,
    String fileName,
    Instant createdAt,
    long fileSize,
    String fileSizeFormatted
) {

  public static String formatFileSize(final long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    if (bytes < 1024 * 1024) {
      return String.format("%.1f KB", bytes / 1024.0);
    }
    return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
  }
}
