package com.simonrowe.media;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

@Component
public class ImageVariantGenerator {

  private static final Map<String, int[]> VARIANT_SPECS = Map.of(
      "thumbnail", new int[]{150, 150},
      "small", new int[]{300, 300},
      "medium", new int[]{600, 600},
      "large", new int[]{1200, 1200}
  );

  private static final Map<String, Double> VARIANT_QUALITY = Map.of(
      "thumbnail", 0.80,
      "small", 0.85,
      "medium", 0.85,
      "large", 0.90
  );

  public Map<String, MediaAsset.VariantInfo> generateVariants(
      final Path originalFile,
      final String assetId,
      final String outputDir
  ) throws IOException {
    String mimeType = java.nio.file.Files.probeContentType(originalFile);
    if (mimeType != null && mimeType.equals("image/svg+xml")) {
      return Map.of();
    }

    Map<String, MediaAsset.VariantInfo> variants = new LinkedHashMap<>();
    String extension = getExtension(originalFile.getFileName().toString());

    for (String variantName : new String[]{
        "thumbnail", "small", "medium", "large"}) {
      int[] dims = VARIANT_SPECS.get(variantName);
      double quality = VARIANT_QUALITY.get(variantName);
      String variantFileName = assetId + "_" + variantName + "." + extension;
      File outputFile = Path.of(outputDir, variantFileName).toFile();

      Thumbnails.of(originalFile.toFile())
          .size(dims[0], dims[1])
          .outputQuality(quality)
          .toFile(outputFile);

      BufferedImage resultImage = ImageIO.read(outputFile);
      int width = resultImage != null ? resultImage.getWidth() : 0;
      int height = resultImage != null ? resultImage.getHeight() : 0;
      long variantFileSize = outputFile.length();

      String relativePath = assetId + "/" + variantFileName;
      variants.put(variantName, new MediaAsset.VariantInfo(
          relativePath, width, height, variantFileSize));
    }

    return variants;
  }

  private String getExtension(final String fileName) {
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex > 0) {
      return fileName.substring(dotIndex + 1).toLowerCase();
    }
    return "jpg";
  }
}
