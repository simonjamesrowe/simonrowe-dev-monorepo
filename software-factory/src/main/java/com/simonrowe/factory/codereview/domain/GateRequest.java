package com.simonrowe.factory.codereview.domain;

/**
 * Asks for the {@code Code Review} check to be worked out again after a person resolved one of
 * this reviewer's threads. Carries no head commit: the gate is recomputed for whatever the pull
 * request's head is when it runs.
 */
public record GateRequest(String owner, String repository, int pullNumber, Long installationId) {
}
