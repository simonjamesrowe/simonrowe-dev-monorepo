package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class MediaSyncServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void syncUploadsDirectoryCreatesVariantsForLegacyRootImage() throws IOException {
    final Path legacyFile = createTestJpeg("legacy-image.jpg", 1600, 1200);
    MediaAssetRepository repository = mock(MediaAssetRepository.class);
    when(repository.findByLegacyId("legacy-image.jpg")).thenReturn(Optional.empty());

    MediaSyncService service = new MediaSyncService(
        repository, new ImageVariantGenerator(), tempDir.toString());

    service.syncUploadsDirectory();

    ArgumentCaptor<MediaAsset> savedAsset = ArgumentCaptor.forClass(MediaAsset.class);
    verify(repository).save(savedAsset.capture());

    MediaAsset asset = savedAsset.getValue();
    assertThat(asset.originalPath()).isEqualTo("/uploads/legacy-image.jpg");
    assertThat(asset.variants()).containsKeys("thumbnail", "small", "medium", "large");
    assertThat(asset.variants().get("small").path())
        .isEqualTo("/uploads/" + asset.id() + "/" + asset.id() + "_small.jpg");
    assertThat(tempDir.resolve(asset.id()).resolve(asset.id() + "_small.jpg")).exists();
    assertThat(legacyFile).exists();
  }

  @Test
  void syncUploadsDirectoryBackfillsVariantsForExistingLegacyAsset() throws IOException {
    createTestJpeg("legacy-image.jpg", 1600, 1200);
    MediaAssetRepository repository = mock(MediaAssetRepository.class);
    MediaAsset existing = new MediaAsset(
        "asset-123",
        "legacy-image.jpg",
        "image/jpeg",
        1024L,
        "/uploads/legacy-image.jpg",
        Map.of(),
        Instant.parse("2026-01-01T10:00:00Z"),
        Instant.parse("2026-01-01T10:00:00Z"),
        "legacy-image.jpg"
    );
    when(repository.findByLegacyId("legacy-image.jpg")).thenReturn(Optional.of(existing));

    MediaSyncService service = new MediaSyncService(
        repository, new ImageVariantGenerator(), tempDir.toString());

    service.syncUploadsDirectory();

    ArgumentCaptor<MediaAsset> savedAsset = ArgumentCaptor.forClass(MediaAsset.class);
    verify(repository).save(savedAsset.capture());

    MediaAsset asset = savedAsset.getValue();
    assertThat(asset.id()).isEqualTo("asset-123");
    assertThat(asset.variants()).containsKeys("thumbnail", "small", "medium", "large");
    assertThat(tempDir.resolve("asset-123").resolve("asset-123_large.jpg")).exists();
  }

  private Path createTestJpeg(final String filename, final int width, final int height)
      throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.ORANGE);
    g.fillRect(0, 0, width, height);
    g.setColor(Color.BLACK);
    g.fillRect(width / 4, height / 4, width / 2, height / 2);
    g.dispose();

    Path file = tempDir.resolve(filename);
    ImageIO.write(image, "jpg", file.toFile());
    return file;
  }
}
