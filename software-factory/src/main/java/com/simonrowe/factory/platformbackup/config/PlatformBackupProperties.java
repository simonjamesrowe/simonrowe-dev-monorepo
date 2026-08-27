package com.simonrowe.factory.platformbackup.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the nightly platform datastore backup.
 *
 * @param enabled whether this container executes the capture. True only on {@code deployer}; see
 *     {@code PlatformBackupActivitiesImpl} for why that condition is load-bearing.
 * @param script absolute path to {@code backup-platform.sh} inside the container, following the
 *     {@code factory.deploy.script} convention
 * @param workingDirectory the deploy directory the script runs from, which is also where it reads
 *     {@code .env}
 * @param timeout how long a capture may take before the activity kills it. Generous on purpose:
 *     the ClickHouse archive is unbounded and the Pi is slow, and a premature kill is
 *     indistinguishable from a real failure.
 */
@ConfigurationProperties(prefix = "factory.platform-backup")
public record PlatformBackupProperties(
    boolean enabled, String script, String workingDirectory, Duration timeout) {
}
