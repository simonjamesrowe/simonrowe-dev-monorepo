package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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

@ExtendWith(MockitoExtension.class)
class BlogImageGenerationServiceTest {

  @Mock private ImageModel imageModel;
  @Mock private ExternalImageDownloader externalImageDownloader;

  @InjectMocks private BlogImageGenerationService service;

  @Test
  void generateAndStore_successfulGeneration() {
    ImageResponse response = new ImageResponse(
        List.of(new ImageGeneration(new Image("https://dalle.example.com/img.png", null))));
    when(imageModel.call(any(ImagePrompt.class))).thenReturn(response);
    when(externalImageDownloader.downloadAndStore("https://dalle.example.com/img.png"))
        .thenReturn("/uploads/abc-123/original.png");

    String result = service.generateAndStore(
        "Week in Review", "Weekly summary of site activity");

    assertThat(result).isEqualTo("/uploads/abc-123/original.png");

    ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(imageModel).call(captor.capture());
    String prompt = captor.getValue().getInstructions().getFirst().getText();
    assertThat(prompt).contains("dark navy blue");
    assertThat(prompt).contains("Week in Review");
    assertThat(prompt).contains("Weekly summary");
  }

  @Test
  void generateAndStore_nullUrl_returnsNull() {
    ImageResponse response = new ImageResponse(
        List.of(new ImageGeneration(new Image(null, null))));
    when(imageModel.call(any(ImagePrompt.class))).thenReturn(response);

    String result = service.generateAndStore("Test Blog", "Summary");

    assertThat(result).isNull();
    verify(externalImageDownloader, never()).downloadAndStore(anyString());
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
  void generateAndStore_nullSummary_stillGenerates() {
    ImageResponse response = new ImageResponse(
        List.of(new ImageGeneration(new Image("https://dalle.example.com/img.png", null))));
    when(imageModel.call(any(ImagePrompt.class))).thenReturn(response);
    when(externalImageDownloader.downloadAndStore(anyString()))
        .thenReturn("/uploads/def-456/original.png");

    String result = service.generateAndStore("My Blog Post", null);

    assertThat(result).isEqualTo("/uploads/def-456/original.png");

    ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(imageModel).call(captor.capture());
    String prompt = captor.getValue().getInstructions().getFirst().getText();
    assertThat(prompt).contains("My Blog Post");
    assertThat(prompt).doesNotContain("null");
  }
}
