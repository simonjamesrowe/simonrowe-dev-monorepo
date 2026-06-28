package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;

@ExtendWith(MockitoExtension.class)
class BlogImageGenerationServiceTest {

  @Mock private ImageModel imageModel;
  @Mock private ExternalImageDownloader externalImageDownloader;

  @InjectMocks private BlogImageGenerationService service;

  private static String promptOf(final ArgumentCaptor<ImagePrompt> captor) {
    return captor.getValue().getInstructions().getFirst().getText();
  }

  @Test
  void generateAndStore_urlResponse_downloadsAndStores() {
    ImageResponse response = new ImageResponse(
        List.of(new ImageGeneration(new Image("https://img.example.com/a.png", null))));
    when(imageModel.call(any(ImagePrompt.class))).thenReturn(response);
    when(externalImageDownloader.downloadAndStore("https://img.example.com/a.png"))
        .thenReturn("/uploads/abc-123/original.png");

    String result = service.generateAndStore(
        "Week in Review", "Weekly summary of site activity");

    assertThat(result).isEqualTo("/uploads/abc-123/original.png");

    ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(imageModel).call(captor.capture());
    assertThat(promptOf(captor)).contains("Week in Review");
    assertThat(promptOf(captor)).contains("Weekly summary");
    assertThat(promptOf(captor)).contains("No text");
  }

  @Test
  void generateAndStore_base64Response_decodesAndStoresBytes() {
    byte[] raw = "some-image-bytes".getBytes();
    String b64 = Base64.getEncoder().encodeToString(raw);
    ImageResponse response = new ImageResponse(
        List.of(new ImageGeneration(new Image(null, b64))));
    when(imageModel.call(any(ImagePrompt.class))).thenReturn(response);
    when(externalImageDownloader.storeImageBytes(any(byte[].class), eq("png"), anyString()))
        .thenReturn("/uploads/xyz-789/original.png");

    String result = service.generateAndStore("Test Blog", "Summary");

    assertThat(result).isEqualTo("/uploads/xyz-789/original.png");

    ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
    verify(externalImageDownloader).storeImageBytes(bytes.capture(), eq("png"), anyString());
    assertThat(bytes.getValue()).isEqualTo(raw);
    verify(externalImageDownloader, never()).downloadAndStore(anyString());
  }

  @Test
  void generateAndStore_noUrlOrBase64_returnsNull() {
    ImageResponse response = new ImageResponse(
        List.of(new ImageGeneration(new Image(null, null))));
    when(imageModel.call(any(ImagePrompt.class))).thenReturn(response);

    String result = service.generateAndStore("Test Blog", "Summary");

    assertThat(result).isNull();
    verify(externalImageDownloader, never()).downloadAndStore(anyString());
    verify(externalImageDownloader, never()).storeImageBytes(any(), anyString(), anyString());
  }

  @Test
  void generateAndStore_imageModelThrows_returnsNull() {
    when(imageModel.call(any(ImagePrompt.class)))
        .thenThrow(new RuntimeException("API timeout"));

    String result = service.generateAndStore("Test Blog", null);

    assertThat(result).isNull();
    verify(externalImageDownloader, never()).downloadAndStore(anyString());
  }

  @Test
  void generateAndStore_usesGptImage1WithValidOptions() {
    ImageResponse response = new ImageResponse(
        List.of(new ImageGeneration(new Image("https://img.example.com/a.png", null))));
    when(imageModel.call(any(ImagePrompt.class))).thenReturn(response);
    when(externalImageDownloader.downloadAndStore(anyString()))
        .thenReturn("/uploads/abc/original.png");

    service.generateAndStore("Test Blog", "Summary");

    ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(imageModel).call(captor.capture());
    OpenAiImageOptions options = (OpenAiImageOptions) captor.getValue().getOptions();
    assertThat(options.getModel()).isEqualTo("gpt-image-1");
    // gpt-image-1 rejects dall-e-3's "standard" quality value
    assertThat(options.getQuality()).isNotEqualTo("standard");
    // gpt-image-1 supports 1536x1024 landscape; 1792x1024 is dall-e-3 only
    assertThat(options.getWidth()).isEqualTo(1536);
    assertThat(options.getHeight()).isEqualTo(1024);
  }

  @Test
  void buildPrompt_isDeterministicForSameTitle() {
    assertThat(service.buildPrompt("My Post", "desc"))
        .isEqualTo(service.buildPrompt("My Post", "desc"));
  }

  @Test
  void buildPrompt_variesAcrossDifferentTitles() {
    List<String> titles = List.of(
        "AI & Tech Roundup: May 4 - May 11, 2026",
        "AI & Tech Roundup: May 11 - May 18, 2026",
        "AI & Tech Roundup: May 18 - May 25, 2026",
        "AI & Tech Roundup: May 25 - May 27, 2026",
        "AI & Tech Roundup: May 27 - Jun 1, 2026",
        "Building a CMS from Scratch",
        "Production-Ready Observability",
        "Adding AI Chat to My Portfolio");

    Set<String> prompts = new HashSet<>();
    for (String title : titles) {
      prompts.add(service.buildPrompt(title, "Latest roundup of site activity"));
    }

    // Distinct titles should not all collapse to the same hero image prompt.
    assertThat(prompts.size()).isGreaterThan(1);
  }

  @Test
  void buildPrompt_alwaysContainsTitleAndNoTextConstraint() {
    String prompt = service.buildPrompt("My Blog Post", null);
    assertThat(prompt).contains("My Blog Post");
    assertThat(prompt).contains("No text");
    assertThat(prompt).doesNotContain("null");
  }
}
