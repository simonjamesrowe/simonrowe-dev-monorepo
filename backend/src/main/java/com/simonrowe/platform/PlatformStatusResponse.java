package com.simonrowe.platform;

import java.util.List;

/**
 * What {@code GET /api/platform/status} returns.
 *
 * <p>The frontend adds its own entry to {@code services} client-side: the backend cannot know
 * which bundle a browser loaded, and a guess would be wrong exactly when it mattered.
 *
 * @param services the first-party JVM services, backend first
 * @param components the third-party images production declares
 */
public record PlatformStatusResponse(
    List<ServiceVersion> services, List<PlatformComponent> components) {
}
