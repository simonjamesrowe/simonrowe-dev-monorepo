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
    BlogContentType contentType
) {

  public static BlogDetailResponse fromEntity(final Blog blog) {
    return fromEntity(blog, blog.featuredImageUrl());
  }

  public static BlogDetailResponse fromEntity(
      final Blog blog,
      final String featuredImageUrl
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
        BlogContentType.orDefault(blog.contentType())
    );
  }
}
