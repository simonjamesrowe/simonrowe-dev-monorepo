package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The manifest is the only thing that makes a months-old archive interpretable,
 * and it is parsed by {@code scripts/restore-platform.sh}, so its shape is a
 * contract rather than a convenience.
 */
class PlatformManifestTest {

  private static final Instant CREATED_AT = Instant.parse("2026-08-25T02:00:07.412Z");

  private PlatformManifest manifest(final Map<String, String> fingerprints) {
    Map<String, PlatformManifest.DumpEntry> databases = new LinkedHashMap<>();
    databases.put("langfuse", new PlatformManifest.DumpEntry("postgres/langfuse.sql", 48213771L));
    databases.put("dtrack", new PlatformManifest.DumpEntry("postgres/dtrack.sql", 19044210L));
    databases.put("temporal", new PlatformManifest.DumpEntry("postgres/temporal.sql", 8871004L));
    databases.put("temporal_visibility",
        new PlatformManifest.DumpEntry("postgres/temporal_visibility.sql", 2210398L));

    Map<String, Long> tables = new LinkedHashMap<>();
    tables.put("traces", 1842991L);
    tables.put("observations", 5120443L);
    tables.put("scores", 88104L);

    Map<String, String> images = new LinkedHashMap<>();
    images.put("langfuse", "langfuse/langfuse:3.212.0");
    images.put("dependencytrack-apiserver", "dependencytrack/apiserver:5.0.3");
    images.put("langfuse-clickhouse", "clickhouse/clickhouse-server:26.7.1.1315");

    return new PlatformManifest(
        CREATED_AT,
        new PlatformManifest.PostgresSection(
            "langfuse-db",
            databases,
            new PlatformManifest.DumpEntry("postgres/roles.sql", 3122L)),
        new PlatformManifest.ClickHouseSection(
            "langfuse-clickhouse", "default", "clickhouse/default.zip", 913448201L, tables),
        images,
        fingerprints);
  }

  private Map<String, String> fingerprints() {
    return SecretFingerprinter.fingerprintAll(name -> "super-secret-" + name);
  }

  @Test
  void pinsTheSchemaVersion() {
    assertThat(manifest(fingerprints()).schemaVersion()).isEqualTo(1);
    assertThat(manifest(fingerprints()).toJson()).contains("\"schemaVersion\": 1");
  }

  @Test
  void recordsTheCaptureTimeAsAnIsoInstant() {
    assertThat(manifest(fingerprints()).toJson())
        .contains("\"createdAt\": \"2026-08-25T02:00:07.412Z\"");
  }

  @Test
  void recordsEveryPostgresDumpWithItsEntryPathAndByteCount() {
    String json = manifest(fingerprints()).toJson();

    assertThat(json)
        .contains("\"postgres/langfuse.sql\"")
        .contains("48213771")
        .contains("\"postgres/dtrack.sql\"")
        .contains("\"postgres/temporal.sql\"")
        .contains("\"postgres/temporal_visibility.sql\"")
        .contains("\"postgres/roles.sql\"");
  }

  @Test
  void recordsClickHousePerTableRowCounts() {
    String json = manifest(fingerprints()).toJson();

    assertThat(json)
        .contains("\"traces\": 1842991")
        .contains("\"observations\": 5120443")
        .contains("\"scores\": 88104");
  }

  /**
   * Image tags are what tell a future operator which tool version produced a
   * dump — a {@code dtrack} dump taken under 5.0.3 and restored into a later
   * major may need that version's own schema migration afterwards.
   */
  @Test
  void recordsTheImageTagOfEachToolAtCaptureTime() {
    assertThat(manifest(fingerprints()).toJson())
        .contains("\"langfuse\": \"langfuse/langfuse:3.212.0\"")
        .contains("\"dependencytrack-apiserver\": \"dependencytrack/apiserver:5.0.3\"")
        .contains("\"langfuse-clickhouse\": \"clickhouse/clickhouse-server:26.7.1.1315\"");
  }

  /**
   * The load-bearing assertion of this whole class. The manifest travels to a
   * cloud folder; a secret leaking into it would be a real exposure, and it would
   * be invisible because the archive otherwise looks correct.
   */
  @Test
  void neverContainsSecretValues() {
    String json = manifest(fingerprints()).toJson();

    assertThat(json).doesNotContain("super-secret");
  }

  @Test
  void carriesAllFourFingerprintKeysEvenWhenValuesAreAbsent() {
    String json = manifest(SecretFingerprinter.fingerprintAll(name -> null)).toJson();

    assertThat(json)
        .contains("\"ENCRYPTION_KEY\": null")
        .contains("\"SALT\": null")
        .contains("\"NEXTAUTH_SECRET\": null")
        .contains("\"DEPENDENCYTRACK_KEK\": null");
  }

  @Test
  void emitsFingerprintsAsHexStringsWhenPresent() {
    String json = manifest(fingerprints()).toJson();

    assertThat(json).containsPattern("\"SALT\": \"[0-9a-f]{64}\"");
  }

  /**
   * The restore script parses this with {@code python3 -c 'json.load(...)'}, so
   * "looks about right" is not enough — it has to actually parse. This really
   * parses it, which catches the classic failure of hand-rolled JSON (a stray
   * trailing comma) that would otherwise only surface during a restore.
   */
  @Test
  void producesJsonThatActuallyParsesWithTheExpectedStructure() throws Exception {
    String json = manifest(fingerprints()).toJson();

    JsonNode root = new ObjectMapper().readTree(json);

    assertThat(root.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(root.get("createdAt").asText()).isEqualTo("2026-08-25T02:00:07.412Z");
    assertThat(root.get("postgres").get("container").asText()).isEqualTo("langfuse-db");
    assertThat(root.get("postgres").get("databases").properties())
        .extracting(Map.Entry::getKey)
        .containsExactly("langfuse", "dtrack", "temporal", "temporal_visibility");
    assertThat(root.get("postgres").get("databases").get("langfuse").get("bytes").asLong())
        .isEqualTo(48213771L);
    assertThat(root.get("postgres").get("roles").get("entry").asText())
        .isEqualTo("postgres/roles.sql");
    assertThat(root.get("clickhouse").get("entry").asText())
        .isEqualTo("clickhouse/default.zip");
    assertThat(root.get("clickhouse").get("tables").get("traces").asLong())
        .isEqualTo(1842991L);
    assertThat(root.get("secretFingerprints").properties())
        .extracting(Map.Entry::getKey)
        .containsExactly("ENCRYPTION_KEY", "SALT", "NEXTAUTH_SECRET",
            "DEPENDENCYTRACK_KEK");
  }

  /** An empty map must still emit {@code {}} rather than dangling punctuation. */
  @Test
  void parsesWhenEveryMapIsEmpty() throws Exception {
    PlatformManifest empty = new PlatformManifest(
        CREATED_AT,
        new PlatformManifest.PostgresSection("langfuse-db", Map.of(),
            new PlatformManifest.DumpEntry("postgres/roles.sql", 0L)),
        new PlatformManifest.ClickHouseSection(
            "langfuse-clickhouse", "default", "clickhouse/default.zip", 0L, Map.of()),
        Map.of(),
        Map.of());

    JsonNode root = new ObjectMapper().readTree(empty.toJson());

    assertThat(root.get("images")).isEmpty();
    assertThat(root.get("clickhouse").get("tables")).isEmpty();
  }

  @Test
  void escapesCharactersThatWouldBreakTheJson() {
    Map<String, String> images = new LinkedHashMap<>();
    images.put("odd", "tag-with-\"quote\"-and-\\backslash");

    PlatformManifest odd = new PlatformManifest(
        CREATED_AT,
        new PlatformManifest.PostgresSection("langfuse-db", Map.of(),
            new PlatformManifest.DumpEntry("postgres/roles.sql", 0L)),
        new PlatformManifest.ClickHouseSection(
            "langfuse-clickhouse", "default", "clickhouse/default.zip", 0L, Map.of()),
        images,
        SecretFingerprinter.fingerprintAll(name -> null));

    assertThat(odd.toJson()).contains("tag-with-\\\"quote\\\"-and-\\\\backslash");
  }

  /**
   * A zero-byte dump is recorded honestly rather than suppressed. The operator
   * needs to see it — a silently omitted entry looks like a clean backup.
   */
  @Test
  void recordsZeroByteDumpsRatherThanOmittingThem() {
    PlatformManifest empty = new PlatformManifest(
        CREATED_AT,
        new PlatformManifest.PostgresSection(
            "langfuse-db",
            Map.of("dtrack", new PlatformManifest.DumpEntry("postgres/dtrack.sql", 0L)),
            new PlatformManifest.DumpEntry("postgres/roles.sql", 0L)),
        new PlatformManifest.ClickHouseSection(
            "langfuse-clickhouse", "default", "clickhouse/default.zip", 0L, Map.of()),
        Map.of(),
        SecretFingerprinter.fingerprintAll(name -> null));

    assertThat(empty.toJson()).contains("\"postgres/dtrack.sql\"").contains("\"bytes\": 0");
  }

}
