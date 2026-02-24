package com.simonrowe.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactRequest(
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 100) String lastName,
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(max = 200) String subject,
    @NotBlank @Size(max = 5000) String message,
    @NotBlank String recaptchaToken
) {
}
