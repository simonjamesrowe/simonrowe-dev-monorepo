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

  /**
   * Returns the most recent published posts, optionally restricted to one content type.
   *
   * <p>The content-type filter is applied <em>before</em> the limit, so a request for
   * three engineering posts returns three even when newer digests occupy the top of the
   * list.
   *
   * @param limit maximum number of posts to return
   * @param contentType the content type to restrict to, or {@code null} for no filtering
   * @return at most {@code limit} posts, newest first
   */
  public List<BlogSummaryResponse> getLatest(
      final int limit,
      final BlogContentType contentType
  ) {
    return blogRepository.findByPublishedTrueOrderByCreatedDateDesc().stream()
        .filter(blog -> contentType == null
            || BlogContentType.orDefault(blog.contentType()) == contentType)
        .limit(limit)
        .map(blog -> BlogSummaryResponse.fromEntity(
            blog,
            mediaVariantResolver.resolvePath(
                blog.featuredImageUrl(), "small", "medium", "large")))
        .toList();
  }
}
