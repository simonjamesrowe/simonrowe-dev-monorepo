package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OriginalMediaPruneServiceTest {

  private static MediaAsset asset(
      final String id, final Map<String, MediaAsset.VariantInfo> variants) {
    return new MediaAsset(
        id, "file.jpg", "image/jpeg", 1000,
        "/uploads/" + id + "/original.jpg", variants,
        Instant.EPOCH, Instant.EPOCH, null);
  }

  private static MediaAsset.VariantInfo variant(final String path) {
    return new MediaAsset.VariantInfo(path, 100, 100, 500);
  }

  @Test
  void bestVariantPathPrefersLarge() {
    MediaAsset a = asset("a", Map.of(
        "thumbnail", variant("/uploads/a/a_thumbnail.jpg"),
        "large", variant("/uploads/a/a_large.jpg")));

    assertThat(OriginalMediaPruneService.bestVariantPath(a))
        .isEqualTo("/uploads/a/a_large.jpg");
  }

  @Test
  void bestVariantPathFallsBackWhenLargeMissing() {
    MediaAsset a = asset("a", Map.of(
        "thumbnail", variant("/uploads/a/a_thumbnail.jpg"),
        "small", variant("/uploads/a/a_small.jpg")));

    assertThat(OriginalMediaPruneService.bestVariantPath(a))
        .isEqualTo("/uploads/a/a_small.jpg");
  }

  @Test
  void bestVariantPathNullWhenNoVariants() {
    assertThat(OriginalMediaPruneService.bestVariantPath(asset("a", Map.of())))
        .isNull();
  }

  @Test
  void rewriteReplacesKnownOriginalReference() {
    String body = "Intro\n\n![diagram](/uploads/a/original.png)\n\nOutro";
    Map<String, String> map = Map.of(
        "/uploads/a/original.png", "/uploads/a/a_large.png");

    assertThat(OriginalMediaPruneService.rewriteReferences(body, map))
        .isEqualTo("Intro\n\n![diagram](/uploads/a/a_large.png)\n\nOutro");
  }

  @Test
  void rewriteStopsAtMarkdownDelimiterNotConsumingClosingParen() {
    String body = "![x](/uploads/a/original.jpg) and text";
    Map<String, String> map = Map.of(
        "/uploads/a/original.jpg", "/uploads/a/a_large.jpg");

    assertThat(OriginalMediaPruneService.rewriteReferences(body, map))
        .isEqualTo("![x](/uploads/a/a_large.jpg) and text");
  }

  @Test
  void rewriteLeavesUnknownReferenceUntouched() {
    // e.g. an SVG original with no variants — not in the map.
    String body = "![logo](/uploads/s/original.svg)";
    Map<String, String> map = Map.of(
        "/uploads/a/original.png", "/uploads/a/a_large.png");

    assertThat(OriginalMediaPruneService.rewriteReferences(body, map))
        .isEqualTo(body);
  }

  @Test
  void rewriteHandlesMultipleMixedReferences() {
    String body = "![a](/uploads/a/original.png) ![s](/uploads/s/original.svg)";
    Map<String, String> map = Map.of(
        "/uploads/a/original.png", "/uploads/a/a_medium.png");

    assertThat(OriginalMediaPruneService.rewriteReferences(body, map))
        .isEqualTo("![a](/uploads/a/a_medium.png) ![s](/uploads/s/original.svg)");
  }

  @Test
  void rewriteReturnsBodyUnchangedWhenNoReferences() {
    String body = "Plain text with no images.";
    assertThat(OriginalMediaPruneService.rewriteReferences(
        body, Map.of("/uploads/a/original.png", "/uploads/a/a_large.png")))
        .isSameAs(body);
  }

  @Test
  void rewriteHandlesNullAndEmpty() {
    Map<String, String> map = Map.of("/uploads/a/original.png", "x");
    assertThat(OriginalMediaPruneService.rewriteReferences(null, map)).isNull();
    assertThat(OriginalMediaPruneService.rewriteReferences("", map)).isEmpty();
  }
}
