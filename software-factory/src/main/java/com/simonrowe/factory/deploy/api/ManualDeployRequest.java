package com.simonrowe.factory.deploy.api;

import jakarta.validation.constraints.Pattern;

/** Exact immutable commit requested by the admin console. */
public record ManualDeployRequest(
    @Pattern(regexp = "[0-9a-fA-F]{40}", message = "sha must be a full commit SHA") String sha) {
}
