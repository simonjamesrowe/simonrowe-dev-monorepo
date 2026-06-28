package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalImageDownloaderTest {

  @Mock private MediaAssetRepository repository;
  @Mock private ImageVariantGenerator variantGenerator;

  @TempDir private Path uploadsDir;

  private ExternalImageDownloader downloader;

  @BeforeEach
  void setUp() {
    downloader = new ExternalImageDownloader(
        repository, variantGenerator, uploadsDir.toString() + "/");
  }

  private static byte[] tinyPng() throws Exception {
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(image, "png", baos);
    return baos.toByteArray();
  }

  @Test
  void storeImageBytes_writesOriginalAndSavesAsset() throws Exception {
    byte[] png = tinyPng();
    when(variantGenerator.generateVariants(any(), anyString(), anyString()))
        .thenReturn(Map.of());

    String path = downloader.storeImageBytes(png, "png", "AI & Tech Roundup");

    assertThat(path).matches("/uploads/[0-9a-f\\-]+/original\\.png");
    String assetId = path.substring("/uploads/".length(), path.lastIndexOf('/'));
    Path written = uploadsDir.resolve(assetId).resolve("original.png");
    assertThat(Files.exists(written)).isTrue();
    assertThat(Files.readAllBytes(written)).isEqualTo(png);

    ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
    verify(repository).save(captor.capture());
    MediaAsset saved = captor.getValue();
    assertThat(saved.id()).isEqualTo(assetId);
    assertThat(saved.originalPath()).isEqualTo(path);
    assertThat(saved.fileName()).isEqualTo("AI & Tech Roundup");
  }

  @Test
  void storeImageBytes_nullData_returnsNull() {
    assertThat(downloader.storeImageBytes(null, "png", "x")).isNull();
    verifyNoInteractions(repository);
  }

  @Test
  void storeImageBytes_emptyData_returnsNull() {
    assertThat(downloader.storeImageBytes(new byte[0], "png", "x")).isNull();
    verifyNoInteractions(repository);
  }
}
