package com.simonrowe.narration;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Narration source for published blog posts.
 *
 * <p>Holds the blog-specific half of what used to be {@code BlogNarrationService}: the
 * published-only lookup, the title-plus-content script, and the length guards. Behaviour is
 * unchanged, including the exact status codes, so {@code /api/blogs/{blogId}/narration}
 * keeps its contract.
 */
@Component
public class BlogNarrationSource implements NarrationSource {

  private static final String AUDIO_ENCODING = "MP3";

  private final BlogRepository blogRepository;
  private final NarrationScriptBuilder scriptBuilder;
  private final NarrationProperties properties;

  public BlogNarrationSource(
      final BlogRepository blogRepository,
      final NarrationScriptBuilder scriptBuilder,
      final NarrationProperties properties
  ) {
    this.blogRepository = blogRepository;
    this.scriptBuilder = scriptBuilder;
    this.properties = properties;
  }

  @Override
  public NarrationContentType contentType() {
    return NarrationContentType.BLOG;
  }

  @Override
  public NarrationDescriptor scriptFor(final String contentId) {
    return descriptor(publishedBlog(contentId));
  }

  @Override
  public boolean isCurrent(final Narration narration) {
    return blogRepository.findByIdAndPublishedTrue(narration.contentId())
        .map(this::descriptor)
        .map(current -> current.id().equals(narration.id()))
        .orElse(false);
  }

  /**
   * The descriptor a blog's current content produces.
   *
   * <p>Package-private rather than private so tests can build the narration a blog would
   * yield without stubbing the repository lookup first.
   *
   * @param blog the blog
   * @return the descriptor
   */
  NarrationDescriptor scriptForBlog(final Blog blog) {
    return descriptor(blog);
  }

  private NarrationDescriptor descriptor(final Blog blog) {
    String script = scriptBuilder.build(blog.title(), blog.content());
    if (script.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Blog has no narratable prose");
    }
    if (script.length() > properties.maxBlogCharacters()) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Blog is too long to narrate");
    }
    String id = scriptBuilder.fingerprint(
        script,
        properties.voiceName(),
        properties.languageCode(),
        AUDIO_ENCODING);
    return new NarrationDescriptor(id, script);
  }

  private Blog publishedBlog(final String blogId) {
    return blogRepository.findByIdAndPublishedTrue(blogId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Blog post not found"));
  }
}
