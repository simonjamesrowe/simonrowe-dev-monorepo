package com.simonrowe.employment;

import com.simonrowe.common.Image;

public record SkillReferenceDto(
    String id,
    String name,
    Double rating,
    Image image,
    String skillGroupId
) {
}
