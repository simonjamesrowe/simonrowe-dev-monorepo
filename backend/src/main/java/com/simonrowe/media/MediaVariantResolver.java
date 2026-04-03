package com.simonrowe.media;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class MediaVariantResolver {

  private final MediaAssetRepository mediaAssetRepository;

  public MediaVariantResolver(final MediaAssetRepository mediaAssetRepository) {
    this.mediaAssetRepository = mediaAssetRepository;
  }

  public String resolvePath(
      final String originalPath,
      final String... preferredVariants
  ) {
    if (originalPath == null || originalPath.isBlank()) {
      return originalPath;
    }

    Optional<MediaAsset> asset = mediaAssetRepository.findByOriginalPath(originalPath);
    if (asset.isEmpty()) {
      return originalPath;
    }

    return selectVariant(asset.get(), preferredVariants)
        .map(MediaAsset.VariantInfo::path)
        .orElse(originalPath);
  }

  private Optional<MediaAsset.VariantInfo> selectVariant(
      final MediaAsset asset,
      final String... preferredVariants
  ) {
    if (asset.variants() == null || asset.variants().isEmpty()) {
      return Optional.empty();
    }

    for (String variantName : List.of(preferredVariants)) {
      MediaAsset.VariantInfo variant = asset.variants().get(variantName);
      if (variant != null && variant.path() != null && !variant.path().isBlank()) {
        return Optional.of(variant);
      }
    }

    return Optional.empty();
  }
}
