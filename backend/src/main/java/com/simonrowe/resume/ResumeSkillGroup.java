package com.simonrowe.resume;

import java.util.List;

public record ResumeSkillGroup(
    String name,
    List<ResumeSkill> skills
) {
}
