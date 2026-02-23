package com.simonrowe.employment;

import com.simonrowe.common.Image;

public record JobSummaryDto(
    String id,
    String title,
    String company,
    String companyUrl,
    Image companyImage,
    String startDate,
    String endDate,
    String location,
    String shortDescription,
    Boolean isEducation,
    Boolean includeOnResume
) {

    public static JobSummaryDto fromEntity(Job job) {
        return new JobSummaryDto(
            job.id(),
            job.title(),
            job.company(),
            job.companyUrl(),
            job.companyImage(),
            job.startDate(),
            job.endDate(),
            job.location(),
            job.shortDescription(),
            job.isEducation(),
            job.includeOnResume()
        );
    }
}
