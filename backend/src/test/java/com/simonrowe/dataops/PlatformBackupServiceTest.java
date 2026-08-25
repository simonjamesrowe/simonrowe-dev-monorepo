package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Exercises the whole capture with a fake {@link CommandRunner} — no Docker, no
 * Postgres, no ClickHouse.
 *
 * <p>That is the point of the seam. This is the one code path in the application
 * whose failures are invisible until someone needs a restore, so it has to be
 * testable somewhere other than a host running the full production stack.
 */
class PlatformBackupServiceTest {

  @TempDir
  private Path clickhouseBackupDir;

  private FakeCommandRunner commands;
  private GoogleDriveService drive;
  private DataOperationsService operations;
  private PlatformBackupService service;
  private byte[] uploaded;

  @BeforeEach
  void setUp() throws IOException {
    commands = new FakeCommandRunner();
    drive = mock(GoogleDriveService.class);
    operations = mock(DataOperationsService.class);
    uploaded = null;

    when(drive.findOrCreatePlatformFolder()).thenReturn("platform-folder");
    when(drive.uploadFile(anyString(), anyString(), any(InputStream.class), anyLong(), any()))
        .thenAnswer(invocation -> {
          uploaded = ((InputStream) invocation.getArgument(2)).readAllBytes();
          return "uploaded-file-id";
        });

    service = new PlatformBackupService(commands, drive, operations, properties(),
        name -> "secret-value-of-" + name);
  }

  private PlatformBackupProperties properties() {
    return new PlatformBackupProperties(
        "docker",
        "langfuse-db",
        "langfuse-clickhouse",
        "default",
        List.of("langfuse", "dtrack", "temporal", "temporal_visibility"),
        clickhouseBackupDir.toString(),
        List.of("langfuse", "dependencytrack-apiserver", "langfuse-clickhouse"));
  }

  // ---------------------------------------------------------------------------
  // Happy path
  // ---------------------------------------------------------------------------

  @Test
  void archiveContainsEveryExpectedEntry() throws Exception {
    assertThat(service.performBackup()).isTrue();

    assertThat(entryNames(uploaded)).containsExactlyInAnyOrder(
        "manifest.json",
        "postgres/roles.sql",
        "postgres/langfuse.sql",
        "postgres/dtrack.sql",
        "postgres/temporal.sql",
        "postgres/temporal_visibility.sql",
        "clickhouse/default.zip");
  }

  @Test
  void manifestByteCountsMatchWhatWasStreamed() throws Exception {
    commands.stdoutFor("-- langfuse dump payload", "pg_dump ", "-d langfuse");

    service.performBackup();

    JsonNode manifest = manifest(uploaded);
    long recorded = manifest.get("postgres").get("databases").get("langfuse")
        .get("bytes").asLong();
    assertThat(recorded).isEqualTo("-- langfuse dump payload".length());
  }

  @Test
  void completesTheOperationWithSummary() {
    assertThat(service.performBackup()).isTrue();

    ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    verify(operations).completeOperation(summary.capture());
    assertThat(summary.getValue()).contains("4 databases");
    verify(operations, never()).failOperation(anyString());
  }

  @Test
  void uploadsToThePlatformFolderNeverTheApplicationFolder() throws Exception {
    service.performBackup();

    verify(drive).findOrCreatePlatformFolder();
    verify(drive, never()).findOrCreateFolder();
    verify(drive).uploadFile(org.mockito.ArgumentMatchers.eq("platform-folder"),
        org.mockito.ArgumentMatchers.startsWith("platform-backup-"),
        any(InputStream.class), anyLong(), any());
  }

  @Test
  void recordsPerTableRowCountsForClickHouse() throws Exception {
    service.performBackup();

    JsonNode tables = manifest(uploaded).get("clickhouse").get("tables");
    assertThat(tables.get("traces").asLong()).isEqualTo(1842991L);
    assertThat(tables.get("observations").asLong()).isEqualTo(5120443L);
  }

  @Test
  void recordsTheImageTagOfEachToolAtCaptureTime() throws Exception {
    service.performBackup();

    JsonNode images = manifest(uploaded).get("images");
    assertThat(images.get("langfuse").asText()).isEqualTo("image-of-langfuse");
    assertThat(images.get("dependencytrack-apiserver").asText())
        .isEqualTo("image-of-dependencytrack-apiserver");
  }

  /**
   * The password must reach {@code pg_dump} through the environment. {@code
   * docker exec -e PGPASSWORD} with a bare name forwards the value from the CLI's
   * own environment, so it appears in no process's argv and cannot be read from
   * {@code ps} on the host.
   */
  @Test
  void passesThePostgresPasswordThroughTheEnvironmentNotTheCommandLine() {
    service.performBackup();

    // "pg_dump", not "pg_dumpall" — the latter also contains the former, so a
    // loose match would silently assert against the roles command instead.
    List<String> dumpCommand = commands.commandMatching("-d dtrack");
    assertThat(dumpCommand).contains("pg_dump", "-e", "PGPASSWORD");
    assertThat(String.join(" ", dumpCommand)).doesNotContain("PGPASSWORD=");

    List<String> rolesCommand = commands.commandMatching("--roles-only");
    assertThat(rolesCommand).contains("pg_dumpall", "-e", "PGPASSWORD");
    assertThat(String.join(" ", rolesCommand)).doesNotContain("PGPASSWORD=");
  }

  // ---------------------------------------------------------------------------
  // Failure paths
  // ---------------------------------------------------------------------------

  @Test
  void failsTheOperationWhenDumpFails() {
    commands.failCommandMatching("-d dtrack", "pg_dump: error: connection failed");

    assertThat(service.performBackup()).isFalse();

    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(operations).failOperation(error.capture());
    assertThat(error.getValue()).contains("connection failed");
    verify(operations, never()).completeOperation(anyString());
  }

  /**
   * The most important assertion in this class. A partial archive that reaches
   * Drive and prunes an older good one is strictly worse than no backup at all.
   */
  @Test
  void uploadsNothingWhenDumpFails() throws Exception {
    commands.failCommandMatching("-d dtrack", "boom");

    service.performBackup();

    verify(drive, never())
        .uploadFile(anyString(), anyString(), any(InputStream.class), anyLong(), any());
    assertThat(uploaded).isNull();
  }

  @Test
  void failsTheOperationWhenTheClickHouseBackupFails() {
    commands.failCommandMatching("BACKUP DATABASE", "not enough disk space");

    assertThat(service.performBackup()).isFalse();
    verify(operations).failOperation(org.mockito.ArgumentMatchers.contains("disk space"));
  }

  @Test
  void failsTheOperationWhenTheUploadFails() throws Exception {
    when(drive.uploadFile(anyString(), anyString(), any(InputStream.class), anyLong(), any()))
        .thenThrow(new IOException("drive rejected the upload"));

    assertThat(service.performBackup()).isFalse();
    verify(operations).failOperation(org.mockito.ArgumentMatchers.contains("drive rejected"));
  }

  /**
   * Exceptions must never escape: the controller runs this on a
   * {@code CompletableFuture}, where a thrown exception would vanish silently and
   * leave the operation mutex held forever.
   */
  @Test
  void neverPropagatesAnException() throws Exception {
    when(drive.findOrCreatePlatformFolder()).thenThrow(new RuntimeException("kaboom"));

    assertThat(service.performBackup()).isFalse();
    verify(operations).failOperation(anyString());
  }

  // ---------------------------------------------------------------------------
  // Cleanup
  // ---------------------------------------------------------------------------

  @Test
  void deletesTheClickHouseVolumeFileOnTheSuccessPath() throws Exception {
    assertThat(service.performBackup()).isTrue();

    try (Stream<Path> remaining = Files.list(clickhouseBackupDir)) {
      assertThat(remaining).isEmpty();
    }
  }

  /**
   * Fails the <em>upload</em> specifically, so ClickHouse has already staged a
   * real file into the shared volume by the time things go wrong. Failing earlier
   * would leave nothing to clean up and the assertion would pass vacuously.
   */
  @Test
  void deletesTheClickHouseVolumeFileOnTheFailurePath() throws Exception {
    when(drive.uploadFile(anyString(), anyString(), any(InputStream.class), anyLong(), any()))
        .thenThrow(new IOException("drive rejected the upload"));

    assertThat(service.performBackup()).isFalse();

    try (Stream<Path> remaining = Files.list(clickhouseBackupDir)) {
      assertThat(remaining).isEmpty();
    }
  }

  @Test
  void deletesTheLocalArchiveOnBothPaths() throws Exception {
    service.performBackup();
    assertThat(service.lastLocalArchive()).matches(path -> !Files.exists(path));

    commands.failCommandMatching("-d dtrack", "boom");
    service.performBackup();
    assertThat(service.lastLocalArchive()).matches(path -> !Files.exists(path));
  }

  /**
   * Residue from crashed prior runs. Without this sweep a few failed nights
   * silently fill the SD card, and the first symptom is an unrelated service
   * failing to write.
   */
  @Test
  void sweepsOrphanedFilesLeftByCrashedPriorRunsBeforeStarting() throws Exception {
    Path orphan = clickhouseBackupDir.resolve("platform-clickhouse-20260101-000000.zip");
    Files.writeString(orphan, "residue from a crashed run");

    service.performBackup();

    assertThat(orphan).doesNotExist();
  }

  @Test
  void sweepsOrphansEvenWhenTheBackupItselfThenFails() throws Exception {
    Path orphan = clickhouseBackupDir.resolve("platform-clickhouse-20260101-000000.zip");
    Files.writeString(orphan, "residue");
    commands.failCommandMatching("pg_dumpall", "boom");

    service.performBackup();

    assertThat(orphan).doesNotExist();
  }

  // ---------------------------------------------------------------------------
  // Secrets
  // ---------------------------------------------------------------------------

  @Test
  void manifestCarriesFingerprintsAndNoSecretValues() throws Exception {
    service.performBackup();

    String manifestJson = entryContent(uploaded, "manifest.json");
    assertThat(manifestJson).containsPattern("\"SALT\": \"[0-9a-f]{64}\"");
    assertThat(manifestJson).doesNotContain("secret-value-of");
  }

  @Test
  void archiveNeverContainsTheEnvironmentFile() throws Exception {
    service.performBackup();

    assertThat(entryNames(uploaded)).noneMatch(name -> name.contains(".env"));
  }

  @Test
  void noSecretValueAppearsAnywhereInTheArchive() throws Exception {
    service.performBackup();

    assertThat(new String(uploaded, StandardCharsets.ISO_8859_1))
        .doesNotContain("secret-value-of");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static List<String> entryNames(final byte[] zip) throws IOException {
    List<String> names = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        names.add(entry.getName());
      }
    }
    return names;
  }

  private static String entryContent(final byte[] zip, final String name) throws IOException {
    try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.getName().equals(name)) {
          return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    }
    throw new IllegalStateException("no entry " + name);
  }

  private static JsonNode manifest(final byte[] zip) throws IOException {
    return new ObjectMapper().readTree(entryContent(zip, "manifest.json"));
  }

  /**
   * A {@link CommandRunner} that answers plausibly for every command the service
   * issues, and can be told to fail a specific one.
   *
   * <p>Matching is by substring of the joined command line, which keeps the tests
   * readable ({@code "-d dtrack"}) at the cost of coupling them loosely to the
   * command shape — an acceptable trade, since the command shape is exactly what
   * these tests are pinning.
   */
  private final class FakeCommandRunner implements CommandRunner {

    private final List<List<String>> issued = new ArrayList<>();
    private final Map<List<String>, String> stdoutOverrides = new LinkedHashMap<>();
    private String failMatch;
    private String failMessage;

    /**
     * Overrides stdout for the one command whose line contains <em>every</em>
     * fragment. All of them, not any — matching on a single fragment would let
     * {@code "pg_dump"} also select {@code pg_dumpall}, and the test would
     * silently assert against the wrong command while still passing.
     */
    void stdoutFor(final String stdout, final String... fragments) {
      stdoutOverrides.put(List.of(fragments), stdout);
    }

    void failCommandMatching(final String match, final String message) {
      this.failMatch = match;
      this.failMessage = message;
    }

    List<String> commandMatching(final String match) {
      return issued.stream()
          .filter(c -> String.join(" ", c).contains(match))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("no command matching " + match));
    }

    @Override
    public long runToStream(final List<String> command, final Map<String, String> extraEnv,
        final OutputStream out) throws CommandFailedException {
      String text = respond(command);
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      try {
        out.write(bytes);
      } catch (IOException ex) {
        throw new CommandFailedException("write failed", ex);
      }
      return bytes.length;
    }

    @Override
    public String runCapturingOutput(final List<String> command,
        final Map<String, String> extraEnv) throws CommandFailedException {
      return respond(command);
    }

    private String respond(final List<String> command) throws CommandFailedException {
      issued.add(List.copyOf(command));
      String line = String.join(" ", command);
      if (failMatch != null && line.contains(failMatch)) {
        throw new CommandFailedException("`" + line + "` exited with 1: " + failMessage);
      }
      for (Map.Entry<List<String>, String> override : stdoutOverrides.entrySet()) {
        if (override.getKey().stream().allMatch(line::contains)) {
          return override.getValue();
        }
      }
      if (line.contains("BACKUP DATABASE")) {
        // ClickHouse writes the archive into the shared volume; emulate that so
        // the service has a real file to read back and then delete.
        writeClickHouseArchive(line);
        return "backup-id\tBACKUP_CREATED";
      }
      if (line.contains("system.parts")) {
        return "traces\t1842991\nobservations\t5120443\nscores\t88104";
      }
      if (line.contains("inspect")) {
        return "image-of-" + command.get(command.size() - 1);
      }
      if (line.contains("pg_dumpall")) {
        return "CREATE ROLE dtrack;\nCREATE ROLE temporal;\n";
      }
      return "-- dump of " + command.get(command.size() - 1) + "\n";
    }

    private void writeClickHouseArchive(final String line) throws CommandFailedException {
      int start = line.indexOf("File('") + "File('".length();
      String fileName = line.substring(start, line.indexOf("')", start));
      try {
        Files.write(clickhouseBackupDir.resolve(fileName),
            "fake clickhouse backup".getBytes(StandardCharsets.UTF_8));
      } catch (IOException ex) {
        throw new CommandFailedException("could not stage clickhouse archive", ex);
      }
    }
  }
}
