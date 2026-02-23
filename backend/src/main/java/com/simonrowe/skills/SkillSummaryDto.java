package com.simonrowe.skills;

import com.simonrowe.common.Image;

public record SkillSummaryDto(
    String id,
    String name,
    Double rating,
    Integer displayOrder,
    String description,
    Image image
) {

  public static SkillSummaryDto fromEntity(Skill skill) {
    return new SkillSummaryDto(
        skill.id(),
        skill.name(),
        skill.rating(),
        skill.displayOrder(),
        skill.description(),
        skill.image()
    );
  }
}
