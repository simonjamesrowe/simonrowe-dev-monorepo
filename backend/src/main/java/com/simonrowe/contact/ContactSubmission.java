package com.simonrowe.contact;

public record ContactSubmission(
    String firstName,
    String lastName,
    String email,
    String subject,
    String message,
    String referrer
) {
}
