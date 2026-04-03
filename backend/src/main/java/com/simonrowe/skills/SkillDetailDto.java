package com.simonrowe.skills;

import com.simonrowe.common.Image;
import java.util.List;

public record SkillDetailDto(
    String id,
    String name,
    Double rating,
    Integer displayOrder,
    String description,
    Image image,
    List<JobReferenceDto> jobs
) {

  public static SkillDetailDto fromEntity(Skill skill, List<JobReferenceDto> jobs) {
    return fromEntity(skill, skill.image(), jobs);
  }

  public static SkillDetailDto fromEntity(
      final Skill skill,
      final Image image,
      final List<JobReferenceDto> jobs
  ) {
    return new SkillDetailDto(
        skill.id(),
        skill.name(),
        skill.rating(),
        skill.displayOrder(),
        skill.description(),
        image,
        jobs
    );
  }
}
