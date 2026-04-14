package com.simonrowe.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.stereotype.Service;

@Service
public class BlogImageGenerationService {

  private static final Logger log =
      LoggerFactory.getLogger(BlogImageGenerationService.class);

  private static final String PROMPT_TEMPLATE =
      "A professional tech blog hero image with a dark navy blue background. "
          + "Abstract digital illustration featuring %s. "
          + "Modern, clean design with glowing blue and white accents, "
          + "geometric shapes, isometric elements, connected nodes, and subtle "
          + "gradients. No text, no words, no letters. Wide landscape format.";

  private final ImageModel imageModel;
  private final ExternalImageDownloader externalImageDownloader;

  public BlogImageGenerationService(
      final ImageModel imageModel,
      final ExternalImageDownloader externalImageDownloader) {
    this.imageModel = imageModel;
    this.externalImageDownloader = externalImageDownloader;
  }

  /**
   * Generates a DALL-E image based on the blog title and summary, stores it
   * with variants, and returns the local path (e.g. /uploads/{assetId}/original.png).
   * Returns null on any failure so callers can proceed without an image.
   */
  public String generateAndStore(
      final String blogTitle,
      final String blogSummary) {
    try {
      String visualDescription = buildVisualDescription(blogTitle, blogSummary);
      String prompt = String.format(PROMPT_TEMPLATE, visualDescription);

      OpenAiImageOptions options = OpenAiImageOptions.builder()
          .model("dall-e-3")
          .width(1792)
          .height(1024)
          .quality("standard")
          .build();

      ImageResponse response = imageModel.call(new ImagePrompt(prompt, options));
      String imageUrl = response.getResult().getOutput().getUrl();

      if (imageUrl == null || imageUrl.isBlank()) {
        log.warn("DALL-E returned no URL for blog: {}", blogTitle);
        return null;
      }

      log.info("Generated image for blog '{}', downloading from {}", blogTitle, imageUrl);
      return externalImageDownloader.downloadAndStore(imageUrl);

    } catch (Exception e) {
      log.warn("Failed to generate image for blog '{}': {}", blogTitle, e.getMessage());
      return null;
    }
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
