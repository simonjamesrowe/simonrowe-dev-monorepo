package com.simonrowe.resume;

public record ResumeJob(
    String title,
    String company,
    String startDate,
    String endDate,
    String location,
    String longDescription
) {
}
