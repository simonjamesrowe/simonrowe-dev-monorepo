package com.simonrowe.skills;

import com.simonrowe.common.Image;
import java.util.List;

public record SkillGroupDetailDto(
    String id,
    String name,
    Double rating,
    Integer displayOrder,
    String description,
    Image image,
    List<SkillDetailDto> skills
) {

  public static SkillGroupDetailDto fromEntity(
      SkillGroup group,
      List<SkillDetailDto> skills
  ) {
    return new SkillGroupDetailDto(
        group.id(),
        group.name(),
        group.rating(),
        group.displayOrder(),
        group.description(),
        group.image(),
        skills
    );
  }
}
