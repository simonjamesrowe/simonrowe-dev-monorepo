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
    BlogContentType contentType
) {

  public static BlogSummaryResponse fromEntity(final Blog blog) {
    return fromEntity(blog, blog.featuredImageUrl());
  }

  public static BlogSummaryResponse fromEntity(
      final Blog blog,
      final String featuredImageUrl
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
        BlogContentType.orDefault(blog.contentType())
    );
  }
}
