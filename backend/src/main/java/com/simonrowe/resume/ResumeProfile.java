package com.simonrowe.resume;

public record ResumeProfile(
    String name,
    String title,
    String email,
    String phone,
    String location,
    String linkedIn,
    String github,
    String website
) {
}
