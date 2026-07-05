package com.simonrowe.migration.changeunits;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.media.ExternalImageDownloader;
import com.simonrowe.media.MediaVariantResolver;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixes broken article images caused by the original media prune, and pulls down
 * any external images to host them locally (resizing and optimizing them).
 */
@ChangeUnit(id = "optimize-article-images", order = "004", author = "simonrowe")
public class V004OptimizeArticleImages {

  private static final Logger LOG = LoggerFactory.getLogger(V004OptimizeArticleImages.class);

  @Execution
  public void execution(
      final AggregatedArticleRepository articleRepository,
      final ExternalImageDownloader imageDownloader,
      final MediaVariantResolver variantResolver) {

    for (AggregatedArticle article : articleRepository.findAll()) {
      String currentUrl = article.imageUrl();
      if (currentUrl == null || currentUrl.isBlank()) {
        continue;
      }

      String newUrl = currentUrl;

      // If it's an external URL, pull it down
      if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://")) {
        LOG.info("Downloading external image for article '{}': {}", article.title(), currentUrl);
        String downloadedPath = imageDownloader.downloadAndStore(currentUrl);
        if (downloadedPath != null) {
          newUrl = downloadedPath;
        } else {
          LOG.warn("Failed to download external image for article '{}'", article.title());
        }
      }

      // If it's an uploads URL (either existing or newly downloaded), resolve to the best variant
      if (newUrl.startsWith("/uploads/")) {
        newUrl = variantResolver.resolvePath(
            newUrl, "large", "medium", "small", "thumbnail");
      }

      // If the URL changed, update the article
      if (!newUrl.equals(currentUrl)) {
        LOG.info(
            "Updating image URL for article '{}': {} -> {}",
            article.title(), currentUrl, newUrl);
        articleRepository.save(withImage(article, newUrl));
      }
    }
  }

  @RollbackExecution
  public void rollback() {
    // Images are additive, and variants remain on disk. Nothing to rollback.
  }

  private AggregatedArticle withImage(final AggregatedArticle article, final String imageUrl) {
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
