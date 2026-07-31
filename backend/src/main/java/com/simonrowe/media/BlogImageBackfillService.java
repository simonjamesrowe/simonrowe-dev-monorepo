package com.simonrowe.media;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Backfill that generates and stores a featured image for any blog that is
 * missing one (e.g. weekly digests created while the image model was broken).
 *
 * <p>Invoked once by a Mongock change unit; safe to re-run because it only
 * touches blogs that still have no image.
 */
@Service
public class BlogImageBackfillService {

  private static final Logger LOG =
      LoggerFactory.getLogger(BlogImageBackfillService.class);

  private final BlogRepository blogRepository;
  private final BlogImageGenerationService imageGenerationService;

  public BlogImageBackfillService(
      final BlogRepository blogRepository,
      final BlogImageGenerationService imageGenerationService) {
    this.blogRepository = blogRepository;
    this.imageGenerationService = imageGenerationService;
  }

  /**
   * Finds every blog without a featured image, generates one, and persists the
   * resulting path. Blogs whose image generation fails are left untouched so the
   * backfill can simply be re-run later.
   */
  public void backfillMissingImages() {
    int generated = 0;
    int failed = 0;

    for (Blog blog : blogRepository.findAll()) {
      if (hasImage(blog)) {
        continue;
      }

      LOG.info("Backfilling featured image for blog '{}' ({})",
          blog.title(), blog.id());
      String imageUrl = imageGenerationService.generateAndStore(
          blog.title(), blog.shortDescription());

      if (imageUrl == null || imageUrl.isBlank()) {
        LOG.warn("Image generation failed for blog '{}' ({}), leaving unchanged",
            blog.title(), blog.id());
        failed++;
        continue;
      }

      blogRepository.save(withImage(blog, imageUrl));
      generated++;
    }

    LOG.info("Blog image backfill complete: {} generated, {} failed", generated, failed);
  }

  private boolean hasImage(final Blog blog) {
    return blog.featuredImageUrl() != null && !blog.featuredImageUrl().isBlank();
  }

  private Blog withImage(final Blog blog, final String imageUrl) {
    return new Blog(
        blog.id(),
        blog.title(),
        blog.shortDescription(),
        blog.content(),
        blog.published(),
        imageUrl,
        blog.createdDate(),
        blog.updatedDate(),
        blog.tags(),
        blog.skills(),
        blog.contentType());
  }
}
