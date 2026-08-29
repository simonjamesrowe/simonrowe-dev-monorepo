package com.simonrowe.blog;

import java.time.Instant;
import java.util.List;

public record BlogDetailResponse(
    String id,
    String title,
    String shortDescription,
    String content,
    String featuredImageUrl,
    Instant createdDate,
    List<TagRef> tags,
    List<SkillRef> skills,
    BlogContentType contentType,
    String shortUrl
) {

  public static BlogDetailResponse fromEntity(final Blog blog) {
    return fromEntity(blog, blog.featuredImageUrl());
  }

  public static BlogDetailResponse fromEntity(
      final Blog blog,
      final String featuredImageUrl
  ) {
    return fromEntity(blog, featuredImageUrl, null);
  }

  /**
   * Builds the detail with a resolved share URL.
   *
   * <p>{@code shortUrl} is the full absolute address, so the frontend never concatenates a
   * base, and is nullable — a post with no link yet renders with no Share control rather
   * than a broken URL.
   *
   * @param blog the post
   * @param featuredImageUrl the resolved image variant
   * @param shortUrl the absolute share URL, or null when the post has no link yet
   * @return the response
   */
  public static BlogDetailResponse fromEntity(
      final Blog blog,
      final String featuredImageUrl,
      final String shortUrl
  ) {
    List<TagRef> tagRefs = blog.tags() == null
        ? List.of()
        : blog.tags().stream().map(TagRef::fromEntity).toList();

    List<SkillRef> skillRefs = blog.skills() == null
        ? List.of()
        : blog.skills().stream().map(SkillRef::fromEntity).toList();

    return new BlogDetailResponse(
        blog.id(),
        blog.title(),
        blog.shortDescription(),
        blog.content(),
        featuredImageUrl,
        blog.createdDate(),
        tagRefs,
        skillRefs,
        BlogContentType.orDefault(blog.contentType()),
        shortUrl
    );
  }
}
