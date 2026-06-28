package com.simonrowe.media;

import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.stereotype.Service;

@Service
public class BlogImageGenerationService {

  private static final Logger log =
      LoggerFactory.getLogger(BlogImageGenerationService.class);

  /**
   * Current OpenAI image generation model. {@code dall-e-3} was retired and now
   * returns HTTP 400 "model does not exist"; {@code gpt-image-1} is its
   * successor. Unlike DALL-E it returns the image inline as base64 rather than a
   * URL, and only accepts specific sizes/qualities (see options below).
   */
  private static final String IMAGE_MODEL = "gpt-image-1";
  private static final int IMAGE_WIDTH = 1536;
  private static final int IMAGE_HEIGHT = 1024;
  private static final String IMAGE_QUALITY = "medium";

  private static final String PROMPT_TEMPLATE =
      "A professional tech blog hero image. Abstract digital illustration on a "
          + "%s background, featuring %s. Modern, clean composition with subtle "
          + "gradients, depth, and a sense of motion. The imagery should evoke: "
          + "%s. No text, no words, no letters, no logos. "
          + "Wide cinematic landscape format.";

  /** Colour palettes selected deterministically per blog title for variety. */
  private static final String[] COLOR_THEMES = {
      "deep navy blue with glowing cyan and white accents",
      "dark charcoal with vibrant amber and warm orange highlights",
      "rich purple and magenta gradients with electric blue accents",
      "deep teal and emerald green with soft mint highlights",
      "dark slate grey with crimson and rose-gold accents",
      "midnight indigo with violet and pink neon glows",
      "forest green and bronze with golden highlights",
      "deep maroon and copper with warm cream accents",
  };

  /** Visual compositions selected deterministically per blog title. */
  private static final String[] COMPOSITIONS = {
      "isometric 3D geometric shapes and connected glowing nodes",
      "flowing wave forms and drifting particle networks",
      "abstract circuit-board patterns and microchip motifs",
      "layered low-poly mountains and crystalline structures",
      "orbiting concentric rings and streaming data flows",
      "interlocking hexagonal grids and floating translucent cubes",
      "swirling nebula-like clouds with constellation points",
      "stacked layered planes with light beams and depth of field",
  };

  private final ImageModel imageModel;
  private final ExternalImageDownloader externalImageDownloader;

  public BlogImageGenerationService(
      final ImageModel imageModel,
      final ExternalImageDownloader externalImageDownloader) {
    this.imageModel = imageModel;
    this.externalImageDownloader = externalImageDownloader;
  }

  /**
   * Generates an image based on the blog title and summary, stores it with
   * variants, and returns the local path (e.g. /uploads/{assetId}/original.png).
   * Returns null on any failure so callers can proceed without an image.
   */
  public String generateAndStore(
      final String blogTitle,
      final String blogSummary) {
    try {
      String prompt = buildPrompt(blogTitle, blogSummary);

      OpenAiImageOptions options = OpenAiImageOptions.builder()
          .model(IMAGE_MODEL)
          .width(IMAGE_WIDTH)
          .height(IMAGE_HEIGHT)
          .quality(IMAGE_QUALITY)
          .build();

      ImageResponse response = imageModel.call(new ImagePrompt(prompt, options));
      Image output = response.getResult().getOutput();

      String imageUrl = output.getUrl();
      if (imageUrl != null && !imageUrl.isBlank()) {
        log.info("Generated image for blog '{}', downloading from {}", blogTitle, imageUrl);
        return externalImageDownloader.downloadAndStore(imageUrl);
      }

      String b64 = output.getB64Json();
      if (b64 != null && !b64.isBlank()) {
        log.info("Generated image for blog '{}' (inline base64)", blogTitle);
        byte[] bytes = Base64.getDecoder().decode(b64);
        return externalImageDownloader.storeImageBytes(bytes, "png", blogTitle);
      }

      log.warn("Image model returned neither URL nor base64 for blog: {}", blogTitle);
      return null;

    } catch (Exception e) {
      log.warn("Failed to generate image for blog '{}': {}", blogTitle, e.getMessage());
      return null;
    }
  }

  /**
   * Builds the image-generation prompt for a blog, deterministically varying the
   * colour palette and composition by title so that posts (especially the weekly
   * roundups, which share a description) don't all look the same.
   */
  String buildPrompt(final String blogTitle, final String blogSummary) {
    String title = blogTitle == null ? "" : blogTitle;
    int hash = title.hashCode();
    String colorTheme = COLOR_THEMES[Math.floorMod(hash, COLOR_THEMES.length)];
    String composition = COMPOSITIONS[Math.floorMod(hash * 31 + 17, COMPOSITIONS.length)];
    String visualDescription = buildVisualDescription(title, blogSummary);
    return String.format(PROMPT_TEMPLATE, colorTheme, composition, visualDescription);
  }

  private String buildVisualDescription(
      final String blogTitle,
      final String blogSummary) {
    StringBuilder sb = new StringBuilder();
    sb.append("tech concepts related to: ").append(blogTitle);
    if (blogSummary != null && !blogSummary.isBlank()) {
      sb.append(". ").append(blogSummary);
    }
    return sb.toString();
  }
}
