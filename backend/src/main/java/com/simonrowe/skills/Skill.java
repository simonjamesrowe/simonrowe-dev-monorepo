package com.simonrowe.skills;

import com.simonrowe.common.Image;

public record Skill(
    String id,
    String name,
    Double rating,
    Integer displayOrder,
    String description,
    Image image
) {
}
