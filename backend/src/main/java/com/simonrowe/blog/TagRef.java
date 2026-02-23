package com.simonrowe.blog;

public record TagRef(String name) {

  public static TagRef fromEntity(final Tag tag) {
    return new TagRef(tag.name());
  }
}
