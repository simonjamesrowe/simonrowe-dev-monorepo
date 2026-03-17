package com.simonrowe.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageVariantGeneratorTest {

  private ImageVariantGenerator generator;

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() {
    generator = new ImageVariantGenerator();
  }

  @Test
  void generateVariantsProducesFourVariantsForJpegImage() throws IOException {
    Path originalFile = createTestJpeg("original.jpg", 2000, 1500);

    Map<String, MediaAsset.VariantInfo> variants =
        generator.generateVariants(originalFile, "asset-001", tempDir.toString());

    assertEquals(4, variants.size());
    assertTrue(variants.containsKey("thumbnail"));
    assertTrue(variants.containsKey("small"));
    assertTrue(variants.containsKey("medium"));
    assertTrue(variants.containsKey("large"));
  }

  @Test
  void generateVariantsThumbnailDoesNotExceedMaxDimensions() throws IOException {
    Path originalFile = createTestJpeg("original.jpg", 2000, 1500);

    Map<String, MediaAsset.VariantInfo> variants =
        generator.generateVariants(originalFile, "asset-001", tempDir.toString());

    MediaAsset.VariantInfo thumbnail = variants.get("thumbnail");
    assertNotNull(thumbnail);
    assertTrue(thumbnail.width() <= 150,
        "Thumbnail width " + thumbnail.width() + " exceeds 150px");
    assertTrue(thumbnail.height() <= 150,
        "Thumbnail height " + thumbnail.height() + " exceeds 150px");
  }

  @Test
  void generateVariantsSmallDoesNotExceedMaxDimensions() throws IOException {
    Path originalFile = createTestJpeg("original.jpg", 2000, 1500);

    Map<String, MediaAsset.VariantInfo> variants =
        generator.generateVariants(originalFile, "asset-001", tempDir.toString());

    MediaAsset.VariantInfo small = variants.get("small");
    assertNotNull(small);
    assertTrue(small.width() <= 300,
        "Small width " + small.width() + " exceeds 300px");
    assertTrue(small.height() <= 300,
        "Small height " + small.height() + " exceeds 300px");
  }

  @Test
  void generateVariantsMediumDoesNotExceedMaxDimensions() throws IOException {
    Path originalFile = createTestJpeg("original.jpg", 2000, 1500);

    Map<String, MediaAsset.VariantInfo> variants =
        generator.generateVariants(originalFile, "asset-001", tempDir.toString());

    MediaAsset.VariantInfo medium = variants.get("medium");
    assertNotNull(medium);
    assertTrue(medium.width() <= 600,
        "Medium width " + medium.width() + " exceeds 600px");
    assertTrue(medium.height() <= 600,
        "Medium height " + medium.height() + " exceeds 600px");
  }

  @Test
  void generateVariantsLargeDoesNotExceedMaxDimensions() throws IOException {
    Path originalFile = createTestJpeg("original.jpg", 2000, 1500);

    Map<String, MediaAsset.VariantInfo> variants =
        generator.generateVariants(originalFile, "asset-001", tempDir.toString());

    MediaAsset.VariantInfo large = variants.get("large");
    assertNotNull(large);
    assertTrue(large.width() <= 1200,
        "Large width " + large.width() + " exceeds 1200px");
    assertTrue(large.height() <= 1200,
        "Large height " + large.height() + " exceeds 1200px");
  }

  @Test
  void generateVariantsAllVariantsHaveNonZeroDimensions() throws IOException {
    Path originalFile = createTestJpeg("original.jpg", 2000, 1500);

    Map<String, MediaAsset.VariantInfo> variants =
        generator.generateVariants(originalFile, "asset-001", tempDir.toString());

    for (Map.Entry<String, MediaAsset.VariantInfo> entry : variants.entrySet()) {
      MediaAsset.VariantInfo info = entry.getValue();
      assertTrue(info.width() > 0,
          "Variant '" + entry.getKey() + "' has zero width");
      assertTrue(info.height() > 0,
          "Variant '" + entry.getKey() + "' has zero height");
      assertTrue(info.fileSize() > 0,
          "Variant '" + entry.getKey() + "' has zero file size");
    }
  }

  @Test
  void generateVariantsAllVariantsHaveNonEmptyPath() throws IOException {
    Path originalFile = createTestJpeg("original.jpg", 2000, 1500);

    Map<String, MediaAsset.VariantInfo> variants =
        generator.generateVariants(originalFile, "asset-001", tempDir.toString());

    for (Map.Entry<String, MediaAsset.VariantInfo> entry : variants.entrySet()) {
      String path = entry.getValue().path();
      assertNotNull(path,
          "Variant '" + entry.getKey() + "' has null path");
      assertTrue(!path.isBlank(),
          "Variant '" + entry.getKey() + "' has blank path");
    }
  }

  @Test
  void generateVariantsReturnEmptyMapForSvgFile() throws IOException {
    Path svgFile = createTestSvg("image.svg");

    Map<String, MediaAsset.VariantInfo> variants =
        generator.generateVariants(svgFile, "asset-svg-001", tempDir.toString());

    assertTrue(variants.isEmpty(),
        "Expected empty map for SVG file but got: " + variants.keySet());
  }

  private Path createTestJpeg(final String filename, final int width, final int height)
      throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.BLUE);
    g.fillRect(0, 0, width, height);
    g.setColor(Color.WHITE);
    g.fillRect(width / 4, height / 4, width / 2, height / 2);
    g.dispose();

    Path file = tempDir.resolve(filename);
    ImageIO.write(image, "jpg", file.toFile());
    return file;
  }

  private Path createTestSvg(final String filename) throws IOException {
    String svgContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">\n"
        + "  <rect width=\"100\" height=\"100\" fill=\"blue\"/>\n"
        + "</svg>\n";

    Path file = tempDir.resolve(filename);
    Files.writeString(file, svgContent);
    return file;
  }
}
