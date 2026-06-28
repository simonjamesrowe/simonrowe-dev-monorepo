package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArticleImageBackfillServiceTest {

  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private BlogImageGenerationService imageGenerationService;

  @InjectMocks private ArticleImageBackfillService service;

  private static AggregatedArticle article(
      final String id, final String title, final String imageUrl) {
    Instant now = Instant.now();
    return new AggregatedArticle(id, title, "Spring Blog",
        "https://spring.io", "https://spring.io/" + id,
        "summary-" + id, "full content", "author", now, now, true, imageUrl);
  }

  @Test
  void backfill_generatesAndSavesForArticlesMissingImage() {
    AggregatedArticle missing = article("a1", "Spring Shell 4.0.3 is out", null);
    AggregatedArticle hasImage = article("a2", "Rundown AI", "/uploads/x/original.png");
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(missing, hasImage));
    when(imageGenerationService.generateAndStore("Spring Shell 4.0.3 is out", "summary-a1"))
        .thenReturn("/uploads/new/original.png");

    service.backfillMissingImages();

    ArgumentCaptor<AggregatedArticle> captor =
        ArgumentCaptor.forClass(AggregatedArticle.class);
    verify(articleRepository).save(captor.capture());
    AggregatedArticle saved = captor.getValue();
    assertThat(saved.id()).isEqualTo("a1");
    assertThat(saved.imageUrl()).isEqualTo("/uploads/new/original.png");
    verify(imageGenerationService, never()).generateAndStore(eq("Rundown AI"), anyString());
  }

  @Test
  void backfill_treatsBlankImageUrlAsMissing() {
    AggregatedArticle blank = article("a1", "Spring post", "  ");
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(blank));
    when(imageGenerationService.generateAndStore(anyString(), anyString()))
        .thenReturn("/uploads/new/original.png");

    service.backfillMissingImages();

    verify(articleRepository).save(any(AggregatedArticle.class));
  }

  @Test
  void backfill_doesNotSaveWhenGenerationFails() {
    AggregatedArticle missing = article("a1", "Spring post", null);
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(missing));
    when(imageGenerationService.generateAndStore(anyString(), anyString()))
        .thenReturn(null);

    service.backfillMissingImages();

    verify(articleRepository, never()).save(any());
  }
}
