package com.simonrowe.media;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Backfill that generates and stores an image for any visible aggregated article
 * that is missing one — notably Spring Blog RSS items, which carry no image in
 * the feed and so render as blank cards on the news page.
 *
 * <p>Invoked once by a Mongock change unit; safe to re-run because it only
 * touches articles that still have no image.
 */
@Service
public class ArticleImageBackfillService {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArticleImageBackfillService.class);

  private final AggregatedArticleRepository articleRepository;
  private final BlogImageGenerationService imageGenerationService;

  public ArticleImageBackfillService(
      final AggregatedArticleRepository articleRepository,
      final BlogImageGenerationService imageGenerationService) {
    this.articleRepository = articleRepository;
    this.imageGenerationService = imageGenerationService;
  }

  /**
   * Finds every visible article without an image, generates one, and persists the
   * resulting path. Articles whose image generation fails are left untouched so
   * the backfill can simply be re-run later.
   */
  public void backfillMissingImages() {
    int generated = 0;
    int failed = 0;

    for (AggregatedArticle article
        : articleRepository.findByVisibleTrueOrderByPublishedDateDesc()) {
      if (hasImage(article)) {
        continue;
      }

      LOG.info("Backfilling image for article '{}' ({})",
          article.title(), article.id());
      String imageUrl = imageGenerationService.generateAndStore(
          article.title(), article.summary());

      if (imageUrl == null || imageUrl.isBlank()) {
        LOG.warn("Image generation failed for article '{}' ({}), leaving unchanged",
            article.title(), article.id());
        failed++;
        continue;
      }

      articleRepository.save(withImage(article, imageUrl));
      generated++;
    }

    LOG.info("Article image backfill complete: {} generated, {} failed",
        generated, failed);
  }

  private boolean hasImage(final AggregatedArticle article) {
    return article.imageUrl() != null && !article.imageUrl().isBlank();
  }

  private AggregatedArticle withImage(
      final AggregatedArticle article, final String imageUrl) {
    return new AggregatedArticle(
        article.id(),
        article.title(),
        article.sourceName(),
        article.sourceUrl(),
        article.originalUrl(),
        article.summary(),
        article.fullContent(),
        article.author(),
        article.publishedDate(),
        article.fetchedAt(),
        article.visible(),
        imageUrl);
  }
}
