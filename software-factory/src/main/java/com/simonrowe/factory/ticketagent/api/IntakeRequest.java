package com.simonrowe.factory.ticketagent.api;

/**
 * Pause or resume the intake schedule.
 *
 * @param paused true to pause
 */
public record IntakeRequest(boolean paused) {
}
