package com.simonrowe.dataops;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the nightly platform datastore backup.
 *
 * <p>Container names and paths are configurable rather than hardcoded so the
 * capture can be exercised against a local stack — including on macOS/OrbStack,
 * where the compose defaults do not apply.
 *
 * @param dockerBinary path to the docker CLI, bind-mounted into this container by
 *     {@code docker-compose.prod.yml}. Same value the redeploy path uses.
 * @param postgresContainer container hosting all four platform databases. Note
 *     that it is one server: {@code langfuse-db} holds {@code langfuse},
 *     {@code dtrack}, {@code temporal} and {@code temporal_visibility}, which is
 *     why stopping it takes down Langfuse <em>and</em> Dependency-Track
 *     <em>and</em> Temporal, and why the restore script never stops it.
 * @param clickhouseContainer container hosting the Langfuse trace store
 * @param clickhouseDatabase the ClickHouse database to back up
 * @param databases the Postgres databases to dump, each independently restorable
 * @param clickhouseBackupPath the shared backup volume as mounted into <em>this</em>
 *     container. ClickHouse's own view of it needs no configuration here: {@code
 *     BACKUP ... TO File('<name>')} resolves a bare relative name against the
 *     server's configured {@code allowed_path}, so the statement never spells out
 *     a directory.
 * @param imageContainers containers whose image tags are recorded in the manifest,
 *     so a restore knows which tool version produced the dump
 */
@ConfigurationProperties(prefix = "backup.platform")
public record PlatformBackupProperties(
    String dockerBinary,
    String postgresContainer,
    String clickhouseContainer,
    String clickhouseDatabase,
    List<String> databases,
    String clickhouseBackupPath,
    List<String> imageContainers
) {
}
