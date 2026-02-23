package com.simonrowe.resume;

import java.util.List;

public record ResumeData(
    ResumeProfile profile,
    List<ResumeJob> employment,
    List<ResumeJob> education,
    List<ResumeSkillGroup> skillGroups
) {
}
