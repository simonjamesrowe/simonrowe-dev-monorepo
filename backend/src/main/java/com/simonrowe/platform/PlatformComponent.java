package com.simonrowe.platform;

/**
 * One third-party container image production is declared to run.
 *
 * <p>This states what {@code docker-compose.prod.yml} <em>declares</em>, not what Docker has
 * resolved. For the pinned majority those are the same thing; for a {@code floating} tag they
 * are not, which is exactly why that flag exists.
 *
 * @param name the compose service name
 * @param image the image reference without its tag
 * @param tag the tag, defaulting to {@code latest} when the reference carries none
 * @param floating true when the tag does not pin a version, so the running digest is unknown
 */
public record PlatformComponent(String name, String image, String tag, boolean floating) {
}
