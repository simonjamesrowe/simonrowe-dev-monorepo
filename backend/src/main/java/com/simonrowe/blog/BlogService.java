package com.simonrowe.blog;

import java.util.List;
import com.simonrowe.media.MediaVariantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BlogService {

  private final BlogRepository blogRepository;
  private final MediaVariantResolver mediaVariantResolver;

  public BlogService(
      final BlogRepository blogRepository,
      final MediaVariantResolver mediaVariantResolver
  ) {
    this.blogRepository = blogRepository;
    this.mediaVariantResolver = mediaVariantResolver;
  }

  public List<BlogSummaryResponse> listPublished() {
    return blogRepository.findByPublishedTrueOrderByCreatedDateDesc().stream()
        .map(blog -> BlogSummaryResponse.fromEntity(
            blog,
            mediaVariantResolver.resolvePath(
                blog.featuredImageUrl(), "small", "medium", "large")))
        .toList();
  }

  public BlogDetailResponse getPublishedById(final String id) {
    return blogRepository.findByIdAndPublishedTrue(id)
        .map(blog -> BlogDetailResponse.fromEntity(
            blog,
            mediaVariantResolver.resolvePath(
                blog.featuredImageUrl(), "large", "medium", "small")))
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Blog post not found"));
  }

  public List<BlogSummaryResponse> getLatest(final int limit) {
    return blogRepository.findByPublishedTrueOrderByCreatedDateDesc().stream()
        .limit(limit)
        .map(blog -> BlogSummaryResponse.fromEntity(
            blog,
            mediaVariantResolver.resolvePath(
                blog.featuredImageUrl(), "small", "medium", "large")))
        .toList();
  }
}
