package com.simonrowe.skills;

import com.simonrowe.common.Image;
import com.simonrowe.employment.Job;

public record JobReferenceDto(
    String id,
    String title,
    String company,
    String startDate,
    String endDate,
    Image companyImage
) {

  public static JobReferenceDto fromEntity(Job job) {
    return fromEntity(job, job.companyImage());
  }

  public static JobReferenceDto fromEntity(final Job job, final Image companyImage) {
    return new JobReferenceDto(
        job.id(),
        job.title(),
        job.company(),
        job.startDate(),
        job.endDate(),
        companyImage
    );
  }
}
