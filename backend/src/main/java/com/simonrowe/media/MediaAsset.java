package com.simonrowe.media;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "media_assets")
public record MediaAsset(
    @Id String id,
    String fileName,
    String mimeType,
    long fileSize,
    String originalPath,
    Map<String, VariantInfo> variants,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId
) {

  public record VariantInfo(
      String path,
      int width,
      int height,
      long fileSize
  ) {
  }
}
