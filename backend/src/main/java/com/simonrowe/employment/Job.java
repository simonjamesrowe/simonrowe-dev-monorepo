package com.simonrowe.employment;

import com.simonrowe.common.Image;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "jobs")
public record Job(
    @Id String id,
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
    List<String> skills
) {
}
