package com.simonrowe.blog;

public record SkillRef(String id, String name) {

  public static SkillRef fromEntity(final Skill skill) {
    return new SkillRef(skill.id(), skill.name());
  }
}
