package com.simonrowe.employment;

import com.simonrowe.common.Image;
import java.util.List;

public record JobDetailDto(
    String id,
    String title,
    String company,
    String companyUrl,
    Image companyImage,
    String startDate,
    String endDate,
    String location,
    String shortDescription,
    String longDescription,
    Boolean isEducation,
    Boolean includeOnResume,
    List<SkillReferenceDto> skills
) {

  public static JobDetailDto fromEntity(Job job, List<SkillReferenceDto> resolvedSkills) {
    return fromEntity(job, job.companyImage(), resolvedSkills);
  }

  public static JobDetailDto fromEntity(
      final Job job,
      final Image companyImage,
      final List<SkillReferenceDto> resolvedSkills
  ) {
    return new JobDetailDto(
        job.id(),
        job.title(),
        job.company(),
        job.companyUrl(),
        companyImage,
        job.startDate(),
        job.endDate(),
        job.location(),
        job.shortDescription(),
        job.longDescription(),
        job.isEducation(),
        job.includeOnResume(),
        resolvedSkills
    );
  }
}
