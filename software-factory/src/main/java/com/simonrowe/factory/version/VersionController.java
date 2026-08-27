package com.simonrowe.factory.version;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports which commit this container was built from.
 *
 * <p><b>Deliberately unauthenticated, unlike every other endpoint in this module.</b> nginx
 * routes only {@code POST /webhooks/github}, so this path is reachable only from inside the
 * Docker network, and the sole thing it discloses is a commit SHA from a public repository.
 *
 * <p>The alternative — reusing {@code X-Factory-Token} as {@code ReviewController} does —
 * would mean giving the backend a token that also authorises {@code /api/reviews}. Widening
 * the backend's privileges to publish a public SHA is a bad trade. Do not add the token check
 * here; add nothing else to this controller either, because the no-auth reasoning holds only
 * for this one payload.
 *
 * <p>This is also the surface that makes deployer drift visible: {@code deployer} excludes
 * itself from its own recreate list, so it does not self-update, and the status page comparing
 * its SHA against the backend's is how that gets noticed rather than discovered months later.
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

  private static final Logger LOG = LoggerFactory.getLogger(VersionController.class);
  private static final String UNKNOWN_COMMIT = "unknown";
  private static final String DEV_SHORT_COMMIT = "dev";
  private static final int SHORT_SHA_LENGTH = 7;

  private final BuildProperties buildProperties;
  private final Instant startedAt;

  @Autowired
  public VersionController(@Nullable final BuildProperties buildProperties) {
    this.buildProperties = buildProperties;
    this.startedAt = Instant.now();
  }

  /**
   * This container's version.
   *
   * @return the version; never null
   */
  @GetMapping
  public FactoryVersion version() {
    String commit = property("commit");
    String resolved = commit == null || commit.isBlank() ? UNKNOWN_COMMIT : commit;
    return new FactoryVersion(
        resolved,
        UNKNOWN_COMMIT.equals(resolved)
            ? DEV_SHORT_COMMIT
            : resolved.substring(0, Math.min(SHORT_SHA_LENGTH, resolved.length())),
        property("commitSubject"),
        commitTime(),
        startedAt);
  }

  private Instant commitTime() {
    String value = property("commitTime");
    if (value == null || value.isBlank()) {
      return null;
    }
    long epochSeconds;
    try {
      epochSeconds = Long.parseLong(value.trim());
    } catch (final NumberFormatException e) {
      LOG.warn("Non-numeric commitTime in build-info.properties: {}", value, e);
      return null;
    }
    // The Gradle task writes 0 when git was unavailable, to keep the output deterministic.
    return epochSeconds == 0L ? null : Instant.ofEpochSecond(epochSeconds);
  }

  private String property(final String key) {
    return buildProperties == null ? null : buildProperties.get(key);
  }
}
