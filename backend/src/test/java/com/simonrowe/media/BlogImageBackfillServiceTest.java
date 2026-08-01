package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlogImageBackfillServiceTest {

  @Mock private BlogRepository blogRepository;
  @Mock private BlogImageGenerationService imageGenerationService;

  @InjectMocks private BlogImageBackfillService service;

  private static Blog blog(final String id, final String title, final String imageUrl) {
    Instant now = Instant.now();
    return new Blog(id, title, "desc-" + id, "content", true,
        imageUrl, now, now, List.of(), List.of(), BlogContentType.ENGINEERING);
  }

  @Test
  void backfill_generatesAndSavesForBlogsMissingImage() {
    Blog missing = blog("b1", "Missing Image Post", null);
    Blog hasImage = blog("b2", "Has Image Post", "/uploads/x/original.png");
    when(blogRepository.findAll()).thenReturn(List.of(missing, hasImage));
    when(imageGenerationService.generateAndStore("Missing Image Post", "desc-b1"))
        .thenReturn("/uploads/new/original.png");

    service.backfillMissingImages();

    ArgumentCaptor<Blog> captor = ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    Blog saved = captor.getValue();
    assertThat(saved.id()).isEqualTo("b1");
    assertThat(saved.featuredImageUrl()).isEqualTo("/uploads/new/original.png");
    // untouched blog never regenerated
    verify(imageGenerationService, never())
        .generateAndStore(eq("Has Image Post"), anyString());
  }

  @Test
  void backfill_treatsBlankImageUrlAsMissing() {
    Blog blank = blog("b1", "Blank Image Post", "   ");
    when(blogRepository.findAll()).thenReturn(List.of(blank));
    when(imageGenerationService.generateAndStore(anyString(), anyString()))
        .thenReturn("/uploads/new/original.png");

    service.backfillMissingImages();

    verify(blogRepository).save(any(Blog.class));
  }

  @Test
  void backfill_doesNotSaveWhenGenerationFails() {
    Blog missing = blog("b1", "Missing Image Post", null);
    when(blogRepository.findAll()).thenReturn(List.of(missing));
    when(imageGenerationService.generateAndStore(anyString(), anyString()))
        .thenReturn(null);

    service.backfillMissingImages();

    verify(blogRepository, never()).save(any());
  }

  @Test
  void backfill_doesNothingWhenAllBlogsHaveImages() {
    Blog hasImage = blog("b1", "Has Image", "/uploads/x/original.png");
    when(blogRepository.findAll()).thenReturn(List.of(hasImage));

    service.backfillMissingImages();

    verify(imageGenerationService, never()).generateAndStore(anyString(), anyString());
    verify(blogRepository, never()).save(any());
  }
}
