package com.simonrowe.skills;

import com.simonrowe.common.Image;
import java.util.Comparator;
import java.util.List;

public record SkillGroupSummaryDto(
    String id,
    String name,
    Double rating,
    Integer displayOrder,
    String description,
    Image image,
    List<SkillSummaryDto> skills
) {

  public static SkillGroupSummaryDto fromEntity(SkillGroup group, List<Skill> skills) {
    return fromEntity(group, group.image(), skills);
  }

  public static SkillGroupSummaryDto fromEntity(
      final SkillGroup group,
      final Image image,
      final List<Skill> skills
  ) {
    List<SkillSummaryDto> mappedSkills = skills == null
        ? List.of()
        : skills.stream()
            .sorted(Comparator.comparingInt(
                s -> s.displayOrder() != null ? s.displayOrder() : 0))
            .map(SkillSummaryDto::fromEntity)
            .toList();

    return new SkillGroupSummaryDto(
        group.id(),
        group.name(),
        group.rating(),
        group.displayOrder(),
        group.description(),
        image,
        mappedSkills
    );
  }
}
