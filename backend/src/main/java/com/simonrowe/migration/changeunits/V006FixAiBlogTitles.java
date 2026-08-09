package com.simonrowe.migration.changeunits;

import com.simonrowe.agents.DigestMetadata;
import com.simonrowe.agents.DigestMetadataGenerator;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Tag;
import com.simonrowe.media.BlogImageGenerationService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retrospectively fixes AI-generated digest blog titles and regenerates featured images.
 */
@ChangeUnit(id = "fix-ai-blog-titles", order = "006", author = "simonrowe")
public class V006FixAiBlogTitles {

  private static final Logger log = LoggerFactory.getLogger(V006FixAiBlogTitles.class);

  @Execution
  public void execution(
      final BlogRepository blogRepository,
      final DigestMetadataGenerator digestMetadataGenerator,
      final BlogImageGenerationService imageGenerationService) {
    
    List<Blog> blogs = blogRepository.findAll();
    
    for (Blog blog : blogs) {
      if (isGenericAiBlog(blog)) {
        log.info(
            "Fixing title and image for generic AI blog ID: {}, current title: {}",
            blog.id(),
            blog.title());
        
        // Pin the model this change unit has already executed against in
        // production: it must keep producing what it produced when it ran,
        // not silently follow aggregation.digest.model as that config moves.
        DigestMetadata metadata = digestMetadataGenerator.generate(
            Collections.emptyList(),
            blog.content(),
            "gpt-4o-mini"
        );
        
        if (metadata != null && metadata.title() != null && !metadata.title().isBlank()) {
          log.info("Generated new title: {}", metadata.title());
          
          // Regenerate image
          String newImageUrl = imageGenerationService.generateAndStore(
              metadata.title(), 
              metadata.shortDescription()
          );
          
          Blog updatedBlog = new Blog(
              blog.id(),
              metadata.title(),
              metadata.shortDescription() != null
                  ? metadata.shortDescription()
                  : blog.shortDescription(),
              blog.content(),
              blog.published(),
              newImageUrl != null ? newImageUrl : blog.featuredImageUrl(),
              blog.createdDate(),
              blog.updatedDate(),
              blog.tags(),
              blog.skills(),
              blog.contentType()
          );
          
          blogRepository.save(updatedBlog);
        } else {
          log.warn("Failed to generate new metadata for blog ID: {}", blog.id());
        }
      }
    }
  }

  private boolean isGenericAiBlog(Blog blog) {
    if (blog.title() == null) {
      return false;
    }
    String lowerTitle = blog.title().toLowerCase();
    if (lowerTitle.contains("this week in ai") || lowerTitle.contains("ai & tech roundup")) {
      return true;
    }
    // Check if it's tagged as 'Weekly Digest' and has generic wording
    if (blog.tags() != null) {
      for (Tag tag : blog.tags()) {
        if ("Weekly Digest".equalsIgnoreCase(tag.name())
            && (lowerTitle.contains("tech roundup")
                || lowerTitle.contains("weekly digest")
                || lowerTitle.contains("ai roundup"))) {
          return true;
        }
      }
    }
    return false;
  }

  @RollbackExecution
  public void rollback() {
    // Left empty as this is a data fix operation involving external LLM/Image APIs.
  }
}
