package com.simonrowe.skills;

import com.simonrowe.common.Image;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "skill_groups")
public record SkillGroup(
    @Id String id,
    String name,
    String description,
    Double rating,
    Integer displayOrder,
    Image image,
    List<Skill> skills
) {
}
