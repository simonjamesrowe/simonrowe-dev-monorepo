package com.simonrowe.blog;

import java.time.Instant;
import java.util.List;

public record BlogSummaryResponse(
    String id,
    String title,
    String shortDescription,
    String featuredImageUrl,
    Instant createdDate,
    List<TagRef> tags,
    List<SkillRef> skills,
    String url,
    BlogContentType contentType,
    String shortUrl
) {

  public static BlogSummaryResponse fromEntity(final Blog blog) {
    return fromEntity(blog, blog.featuredImageUrl());
  }

  public static BlogSummaryResponse fromEntity(
      final Blog blog,
      final String featuredImageUrl
  ) {
    return fromEntity(blog, featuredImageUrl, null);
  }

  /**
   * Builds the summary with a resolved share URL.
   *
   * <p>{@code shortUrl} is the full absolute address, so the frontend never concatenates a
   * base. It is <b>nullable</b> and the Share control is simply absent when it is null: an
   * item created in the window before its link was minted has to render fine rather than
   * hand out a broken URL.
   *
   * @param blog the post
   * @param featuredImageUrl the resolved image variant
   * @param shortUrl the absolute share URL, or null when the post has no link yet
   * @return the response
   */
  public static BlogSummaryResponse fromEntity(
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

    return new BlogSummaryResponse(
        blog.id(),
        blog.title(),
        blog.shortDescription(),
        featuredImageUrl,
        blog.createdDate(),
        tagRefs,
        skillRefs,
        "/blogs/" + blog.id(),
        BlogContentType.orDefault(blog.contentType()),
        shortUrl
    );
  }
}
