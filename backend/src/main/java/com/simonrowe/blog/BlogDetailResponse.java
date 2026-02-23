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
    List<SkillRef> skills
) {

  public static BlogDetailResponse fromEntity(final Blog blog) {
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
        blog.featuredImageUrl(),
        blog.createdDate(),
        tagRefs,
        skillRefs
    );
  }
}
