package com.simonrowe.media;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MediaAssetRepository extends MongoRepository<MediaAsset, String> {

  Page<MediaAsset> findByFileNameContainingIgnoreCase(
      String fileName, Pageable pageable);

  Page<MediaAsset> findByMimeType(String mimeType, Pageable pageable);

  Page<MediaAsset> findByFileNameContainingIgnoreCaseAndMimeType(
      String fileName, String mimeType, Pageable pageable);

  Optional<MediaAsset> findByLegacyId(String legacyId);
}
