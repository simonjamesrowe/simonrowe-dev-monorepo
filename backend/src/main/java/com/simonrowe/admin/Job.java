package com.simonrowe.admin;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "jobs")
public record Job(
    @Id String id,
    String title,
    String company,
    String companyUrl,
    String companyImage,
    String startDate,
    String endDate,
    String location,
    String shortDescription,
    String longDescription,
    boolean education,
    boolean includeOnResume,
    List<String> skills,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId
) {
}
