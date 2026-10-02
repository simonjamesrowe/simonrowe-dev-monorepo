package com.simonrowe.media;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MediaAssetRepository extends MongoRepository<MediaAsset, String> {

  Optional<MediaAsset> findByOriginalPath(String originalPath);

  Page<MediaAsset> findByFileNameContainingIgnoreCase(
      String fileName, Pageable pageable);

  Page<MediaAsset> findByMimeTypeIn(Collection<String> mimeTypes, Pageable pageable);

  Page<MediaAsset> findByFileNameContainingIgnoreCaseAndMimeTypeIn(
      String fileName, Collection<String> mimeTypes, Pageable pageable);

  Optional<MediaAsset> findByLegacyId(String legacyId);
}
