package com.simonrowe.admin;

import com.simonrowe.common.Image;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

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
    @Field("isEducation") Boolean education,
    Boolean includeOnResume,
    List<String> skills,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId
) {
}
