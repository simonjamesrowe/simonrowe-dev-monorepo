package com.simonrowe.dataops;

import java.time.Instant;
import java.util.Map;
import org.springframework.lang.Nullable;

/**
 * Self-description of a platform backup archive, written as {@code manifest.json}.
 *
 * <p>This is what makes a months-old archive interpretable and safely restorable:
 * without it you have five opaque SQL files and a zip, with no way to tell whether
 * a dump is truncated, which tool version produced it, or whether the host's
 * secrets still match the ones the data was encrypted under.
 *
 * <p>It is written <em>last</em>, because the byte counts and row counts it
 * records are only known once the dumps have completed.
 *
 * <p>Serialisation is hand-rolled in the same style as
 * {@link BackupService}'s manifest, for the same reasons: no new dependency, and
 * nothing for GraalVM's native image to reflect over. {@code
 * scripts/restore-platform.sh} parses the result with {@code python3}, so it has
 * to be genuinely well-formed rather than merely plausible —
 * {@code PlatformManifestTest} asserts that.
 */
public record PlatformManifest(
    int schemaVersion,
    Instant createdAt,
    PostgresSection postgres,
    ClickHouseSection clickhouse,
    Map<String, String> images,
    Map<String, String> secretFingerprints
) {

  /**
   * Bump this whenever the archive layout or the fingerprint scheme changes, so a
   * restore can <em>detect</em> an older archive rather than misreading it.
   */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  /** Convenience constructor pinning the current schema version. */
  public PlatformManifest(
      final Instant createdAt,
      final PostgresSection postgres,
      final ClickHouseSection clickhouse,
      final Map<String, String> images,
      final Map<String, String> secretFingerprints) {
    this(CURRENT_SCHEMA_VERSION, createdAt, postgres, clickhouse, images,
        secretFingerprints);
  }

  /** One dump inside the archive: where it lives and how big it turned out. */
  public record DumpEntry(String entry, long bytes) {
  }

  /**
   * The Postgres half of the archive.
   *
   * <p>{@code roles} is separate from {@code databases} because roles restore
   * conditionally — only when absent, since the {@code *-db-init} compose services
   * normally own them — while databases restore unconditionally.
   */
  public record PostgresSection(
      String container,
      Map<String, DumpEntry> databases,
      DumpEntry roles
  ) {
  }

  /**
   * The ClickHouse half of the archive.
   *
   * <p>{@code tables} carries per-table row counts, which are the only practical
   * way to verify a ClickHouse restore landed everything: the archive itself is
   * ClickHouse's own opaque {@code BACKUP} output, deliberately stored without
   * inspection so it stays correct across Langfuse version bumps.
   */
  public record ClickHouseSection(
      String container,
      String database,
      String entry,
      long bytes,
      Map<String, Long> tables
  ) {
  }

  /**
   * Renders the manifest as pretty-printed JSON.
   *
   * @return the JSON document, newline-terminated
   */
  public String toJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"schemaVersion\": ").append(schemaVersion).append(",\n");
    sb.append("  \"createdAt\": ").append(quote(createdAt.toString())).append(",\n");

    sb.append("  \"postgres\": {\n");
    sb.append("    \"container\": ").append(quote(postgres.container())).append(",\n");
    sb.append("    \"databases\": {\n");
    appendDumpEntries(sb, postgres.databases(), "      ");
    sb.append("    },\n");
    sb.append("    \"roles\": ");
    appendDumpEntry(sb, postgres.roles());
    sb.append("\n  },\n");

    sb.append("  \"clickhouse\": {\n");
    sb.append("    \"container\": ").append(quote(clickhouse.container())).append(",\n");
    sb.append("    \"database\": ").append(quote(clickhouse.database())).append(",\n");
    sb.append("    \"entry\": ").append(quote(clickhouse.entry())).append(",\n");
    sb.append("    \"bytes\": ").append(clickhouse.bytes()).append(",\n");
    sb.append("    \"tables\": {\n");
    appendLongMap(sb, clickhouse.tables(), "      ");
    sb.append("    }\n");
    sb.append("  },\n");

    sb.append("  \"images\": {\n");
    appendStringMap(sb, images, "    ");
    sb.append("  },\n");

    sb.append("  \"secretFingerprints\": {\n");
    appendStringMap(sb, secretFingerprints, "    ");
    sb.append("  }\n");

    sb.append("}\n");
    return sb.toString();
  }

  private static void appendDumpEntries(final StringBuilder sb,
      final Map<String, DumpEntry> entries, final String indent) {
    int i = 0;
    for (Map.Entry<String, DumpEntry> e : entries.entrySet()) {
      sb.append(indent).append(quote(e.getKey())).append(": ");
      appendDumpEntry(sb, e.getValue());
      sb.append(i < entries.size() - 1 ? ",\n" : "\n");
      i++;
    }
  }

  private static void appendDumpEntry(final StringBuilder sb, final DumpEntry entry) {
    sb.append("{ \"entry\": ").append(quote(entry.entry()))
        .append(", \"bytes\": ").append(entry.bytes()).append(" }");
  }

  private static void appendStringMap(final StringBuilder sb,
      final Map<String, String> values, final String indent) {
    int i = 0;
    for (Map.Entry<String, String> e : values.entrySet()) {
      sb.append(indent).append(quote(e.getKey())).append(": ")
          .append(e.getValue() == null ? "null" : quote(e.getValue()));
      sb.append(i < values.size() - 1 ? ",\n" : "\n");
      i++;
    }
  }

  private static void appendLongMap(final StringBuilder sb,
      final Map<String, Long> values, final String indent) {
    int i = 0;
    for (Map.Entry<String, Long> e : values.entrySet()) {
      sb.append(indent).append(quote(e.getKey())).append(": ").append(e.getValue());
      sb.append(i < values.size() - 1 ? ",\n" : "\n");
      i++;
    }
  }

  /**
   * Quotes and escapes a JSON string value. Image tags and entry paths are tame in
   * practice, but a stray quote or backslash from a container label would produce
   * a manifest that only fails when a restore tries to parse it — months later,
   * under duress.
   */
  private static String quote(@Nullable final String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(value.length() + 2);
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
