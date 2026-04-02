package com.simonrowe.media;

import com.simonrowe.common.Image;
import com.simonrowe.common.ImageFormat;
import com.simonrowe.common.ImageFormats;
import org.springframework.stereotype.Service;

@Service
public class MediaImageHydrator {

  private final MediaAssetRepository mediaAssetRepository;

  public MediaImageHydrator(final MediaAssetRepository mediaAssetRepository) {
    this.mediaAssetRepository = mediaAssetRepository;
  }

  public Image hydrate(final Image image, final String... preferredVariants) {
    if (image == null || image.url() == null || image.url().isBlank()) {
      return image;
    }

    MediaAsset asset = mediaAssetRepository.findByOriginalPath(image.url()).orElse(null);
    if (asset == null) {
      return image;
    }

    ImageFormats formats = toFormats(asset);
    MediaAsset.VariantInfo preferred = selectVariant(asset, preferredVariants);

    String resolvedUrl = preferred != null ? preferred.path() : image.url();
    Integer width = preferred != null ? preferred.width() : image.width();
    Integer height = preferred != null ? preferred.height() : image.height();
    String name = image.name() != null ? image.name() : asset.fileName();
    String mime = image.mime() != null ? image.mime() : asset.mimeType();

    return new Image(resolvedUrl, name, width, height, mime, formats);
  }

  private ImageFormats toFormats(final MediaAsset asset) {
    if (asset.variants() == null || asset.variants().isEmpty()) {
      return null;
    }

    return new ImageFormats(
        toFormat(asset.variants().get("thumbnail")),
        toFormat(asset.variants().get("small")),
        toFormat(asset.variants().get("medium")),
        toFormat(asset.variants().get("large"))
    );
  }

  private ImageFormat toFormat(final MediaAsset.VariantInfo info) {
    if (info == null || info.path() == null || info.path().isBlank()) {
      return null;
    }
    return new ImageFormat(info.path(), info.width(), info.height());
  }

  private MediaAsset.VariantInfo selectVariant(
      final MediaAsset asset,
      final String... preferredVariants
  ) {
    if (asset.variants() == null || asset.variants().isEmpty()) {
      return null;
    }

    for (String variantName : preferredVariants) {
      MediaAsset.VariantInfo variant = asset.variants().get(variantName);
      if (variant != null && variant.path() != null && !variant.path().isBlank()) {
        return variant;
      }
    }

    return null;
  }
}
