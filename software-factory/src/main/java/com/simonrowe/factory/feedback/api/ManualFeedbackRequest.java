package com.simonrowe.factory.feedback.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Internal API payload for a manually requested feedback run. */
public record ManualFeedbackRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+") String owner,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+") String repository,
    @Min(1) int pullNumber,
    boolean dryRun) {
}
