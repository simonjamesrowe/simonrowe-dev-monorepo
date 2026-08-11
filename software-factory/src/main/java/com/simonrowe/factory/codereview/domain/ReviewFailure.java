package com.simonrowe.factory.codereview.domain;

/**
 * Why a review did not produce a report.
 *
 * <p>Carries the phase as well as the reason: "failed in REVIEWING" and "failed in PUBLISHING" call
 * for completely different investigations, and the reason alone rarely distinguishes them.
 */
public record ReviewFailure(ReviewPhase phase, String reason, String workflowId) {
}
