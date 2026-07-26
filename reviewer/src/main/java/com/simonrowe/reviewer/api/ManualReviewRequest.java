package com.simonrowe.reviewer.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Internal API payload for a manually requested review. */
public record ManualReviewRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+") String owner,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+") String repository,
    @Min(1) int pullNumber,
    @Pattern(regexp = "([a-fA-F0-9]{40})?") String expectedHeadSha,
    boolean publish) {
}
