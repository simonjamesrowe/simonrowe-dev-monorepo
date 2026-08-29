package com.simonrowe.blog;

import com.simonrowe.media.MediaVariantResolver;
import com.simonrowe.shortlink.ShortLinkContentType;
import com.simonrowe.shortlink.ShortLinkService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BlogService {

  private final BlogRepository blogRepository;
  private final MediaVariantResolver mediaVariantResolver;
  private final ShortLinkService shortLinkService;

  public BlogService(
      final BlogRepository blogRepository,
      final MediaVariantResolver mediaVariantResolver,
      final ShortLinkService shortLinkService
  ) {
    this.blogRepository = blogRepository;
    this.mediaVariantResolver = mediaVariantResolver;
    this.shortLinkService = shortLinkService;
  }

  public List<BlogSummaryResponse> listPublished() {
    return toSummaries(blogRepository.findByPublishedTrueOrderByCreatedDateDesc());
  }

  public BlogDetailResponse getPublishedById(final String id) {
    return blogRepository.findByIdAndPublishedTrue(id)
        .map(blog -> BlogDetailResponse.fromEntity(
            blog,
            mediaVariantResolver.resolvePath(
                blog.featuredImageUrl(), "large", "medium", "small"),
            shortLinkService.urlFor(ShortLinkContentType.BLOG, blog.id()).orElse(null)))
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
    return toSummaries(blogRepository.findByPublishedTrueOrderByCreatedDateDesc().stream()
        .filter(blog -> contentType == null
            || BlogContentType.orDefault(blog.contentType()) == contentType)
        .limit(limit)
        .toList());
  }

  /**
   * Maps posts to summaries, resolving every share URL in one query.
   *
   * <p>Batched rather than per-post: the listing is unpaged (~43 posts today), so a
   * lookup per card would turn one render into forty-odd round trips for a field that is
   * only used to populate a button.
   */
  private List<BlogSummaryResponse> toSummaries(final List<Blog> blogs) {
    Map<String, String> shortUrls = shortLinkService.urlsFor(
        ShortLinkContentType.BLOG, blogs.stream().map(Blog::id).toList());

    return blogs.stream()
        .map(blog -> BlogSummaryResponse.fromEntity(
            blog,
            mediaVariantResolver.resolvePath(
                blog.featuredImageUrl(), "small", "medium", "large"),
            shortUrls.get(blog.id())))
        .toList();
  }
}
