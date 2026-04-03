package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.simonrowe.common.Image;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MediaImageHydratorTest {

  @Test
  void hydratePopulatesFormatsAndUsesPreferredVariant() {
    MediaAssetRepository repository = mock(MediaAssetRepository.class);
    MediaImageHydrator hydrator = new MediaImageHydrator(repository);

    MediaAsset asset = new MediaAsset(
        "asset-1",
        "sample.jpg",
        "image/jpeg",
        1024L,
        "/uploads/sample.jpg",
        Map.of(
            "thumbnail", new MediaAsset.VariantInfo("/uploads/asset-1/thumb.jpg", 150, 100, 120L),
            "medium", new MediaAsset.VariantInfo("/uploads/asset-1/medium.jpg", 600, 400, 480L),
            "large", new MediaAsset.VariantInfo("/uploads/asset-1/large.jpg", 1200, 800, 980L)
        ),
        Instant.parse("2026-01-01T10:00:00Z"),
        Instant.parse("2026-01-01T10:00:00Z"),
        null
    );
    given(repository.findByOriginalPath("/uploads/sample.jpg"))
        .willReturn(Optional.of(asset));

    Image hydrated = hydrator.hydrate(
        new Image("/uploads/sample.jpg", "sample.jpg", 2000, 1500, "image/jpeg", null),
        "medium", "large");

    assertThat(hydrated.url()).isEqualTo("/uploads/asset-1/medium.jpg");
    assertThat(hydrated.width()).isEqualTo(600);
    assertThat(hydrated.height()).isEqualTo(400);
    assertThat(hydrated.formats()).isNotNull();
    assertThat(hydrated.formats().thumbnail().url()).isEqualTo("/uploads/asset-1/thumb.jpg");
    assertThat(hydrated.formats().large().url()).isEqualTo("/uploads/asset-1/large.jpg");
  }

  @Test
  void hydrateReturnsOriginalImageWhenMediaAssetMissing() {
    MediaAssetRepository repository = mock(MediaAssetRepository.class);
    MediaImageHydrator hydrator = new MediaImageHydrator(repository);
    Image original = new Image("/uploads/sample.jpg", "sample.jpg", 2000, 1500, "image/jpeg", null);

    given(repository.findByOriginalPath("/uploads/sample.jpg"))
        .willReturn(Optional.empty());

    assertThat(hydrator.hydrate(original, "small", "thumbnail")).isEqualTo(original);
  }
}
