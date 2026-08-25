package com.simonrowe.dataops;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Captures the platform datastores — the four Postgres databases in
 * {@code langfuse-db} plus the ClickHouse {@code default} database — into one
 * dated archive and uploads it to its own Google Drive folder.
 *
 * <p>Runs here rather than in a host script because the backend already runs as
 * {@code user: "0:0"} with the docker socket and CLI bind-mounted (the same
 * arrangement {@link RedeployService} uses), so {@code docker exec langfuse-db
 * pg_dump} needs no new host prerequisite — and because it reuses, rather than
 * reimplements, {@link GoogleDriveService}'s tuned resumable upload, {@link
 * DataOperationsService}'s progress stream and mutex, and {@link
 * BackupRetentionService}'s prune.
 *
 * <p>The accepted cost is that a wedged backend means no platform backup that
 * night. That is already true of the application backup, {@code monitor-prod.sh}
 * restarts an unhealthy backend within a minute, and a dead host defeats a
 * container and a host cron equally.
 *
 * <p>Restore is deliberately <em>not</em> here: see
 * {@code scripts/restore-platform.sh}.
 */
@Service
public class PlatformBackupService {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformBackupService.class);
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final String ROLES_ENTRY = "postgres/roles.sql";
  private static final String CLICKHOUSE_ENTRY_PREFIX = "clickhouse/";
  /** Prefix for the intermediate file in the shared ClickHouse volume. */
  private static final String CLICKHOUSE_STAGING_PREFIX = "platform-clickhouse-";

  private final CommandRunner commands;
  private final GoogleDriveService googleDriveService;
  private final DataOperationsService operationsService;
  private final PlatformBackupProperties properties;
  private final UnaryOperator<String> environment;

  @Nullable
  private Path lastLocalArchive;

  // Two constructors, so Spring needs telling which one to inject. Without this
  // the whole application context fails to start with "No default constructor
  // found" — caught by the existing Spring-context backup tests.
  @Autowired
  public PlatformBackupService(
      final CommandRunner commands,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService,
      final PlatformBackupProperties properties
  ) {
    this(commands, googleDriveService, operationsService, properties, System::getenv);
  }

  /**
   * Constructor taking an explicit environment lookup, so tests can supply secret
   * values without mutating the process environment.
   *
   * @param commands the command seam
   * @param googleDriveService upload and folder resolution
   * @param operationsService progress reporting and completion
   * @param properties container names, database list and paths
   * @param environment resolves an environment variable name to its value
   */
  PlatformBackupService(
      final CommandRunner commands,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService,
      final PlatformBackupProperties properties,
      final UnaryOperator<String> environment
  ) {
    this.commands = commands;
    this.googleDriveService = googleDriveService;
    this.operationsService = operationsService;
    this.properties = properties;
    this.environment = environment;
  }

  /**
   * Captures every platform datastore and uploads the archive.
   *
   * <p>Never throws. The controller runs this on a {@code CompletableFuture},
   * where an escaping exception would vanish silently and leave the operation
   * mutex held forever, so failures are converted into
   * {@link DataOperationsService#failOperation(String)} and a {@code false}
   * return — matching {@link BackupService#performBackup()}.
   *
   * @return {@code true} if the archive was captured and uploaded successfully
   */
  public boolean performBackup() {
    Path archive = null;
    String clickhouseStagingFile = null;
    try {
      // Step 0. Residue from crashed prior runs. Without this a few failed nights
      // silently fill the SD card, and the first symptom is some unrelated
      // service failing to write.
      operationsService.updateProgress("Sweeping orphaned backup files...", 2);
      sweepClickHouseOrphans();

      String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
      String fileName = "platform-backup-" + timestamp + ".zip";
      clickhouseStagingFile = CLICKHOUSE_STAGING_PREFIX + timestamp + ".zip";
      archive = createOwnerOnlyTempFile();
      lastLocalArchive = archive;

      // Resolved before any capture work: a Drive problem should fail fast rather
      // than after an hour of dumping.
      String folderId = googleDriveService.findOrCreatePlatformFolder();

      Map<String, PlatformManifest.DumpEntry> databaseDumps = new LinkedHashMap<>();
      PlatformManifest.DumpEntry rolesDump;
      PlatformManifest.ClickHouseSection clickhouse;

      try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(archive));
           ZipOutputStream zos = new ZipOutputStream(fos)) {

        operationsService.updateProgress("Exporting Postgres roles...", 5);
        rolesDump = writeEntry(zos, ROLES_ENTRY, rolesDumpCommand());

        int progress = 10;
        int progressPerDatabase = 40 / Math.max(1, properties.databases().size());
        for (String database : properties.databases()) {
          operationsService.updateProgress("Exporting database: " + database, progress);
          String entry = "postgres/" + database + ".sql";
          databaseDumps.put(database, writeEntry(zos, entry, dumpCommand(database)));
          progress += progressPerDatabase;
        }

        operationsService.updateProgress("Backing up ClickHouse...", 55);
        clickhouse = captureClickHouse(zos, clickhouseStagingFile);

        operationsService.updateProgress("Writing manifest...", 72);
        PlatformManifest manifest = new PlatformManifest(
            Instant.now(),
            new PlatformManifest.PostgresSection(
                properties.postgresContainer(), databaseDumps, rolesDump),
            clickhouse,
            readImageTags(),
            SecretFingerprinter.fingerprintAll(environment));
        zos.putNextEntry(new ZipEntry("manifest.json"));
        zos.write(manifest.toJson().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      operationsService.updateProgress("Uploading to Google Drive...", 75);
      long size = Files.size(archive);
      try (InputStream in = Files.newInputStream(archive)) {
        googleDriveService.uploadFile(folderId, fileName, in, size,
            (sent, total) -> operationsService.updateProgress(
                String.format("Uploading to Google Drive... %d%% (%s / %s)",
                    total > 0 ? (int) ((sent * 100L) / total) : 0,
                    BackupMetadata.formatFileSize(sent),
                    BackupMetadata.formatFileSize(total)),
                uploadProgressPercent(sent, total)));
      }

      operationsService.completeOperation(
          summarise(databaseDumps, rolesDump, clickhouse, size));
      return true;

    } catch (Exception ex) {
      LOG.error("Platform backup failed", ex);
      operationsService.failOperation("Platform backup failed: " + ex.getMessage());
      return false;
    } finally {
      // Both paths. A failed run must leave nothing behind, or the next few
      // failures compound into a full disk.
      deleteQuietly(archive);
      if (clickhouseStagingFile != null) {
        deleteQuietly(clickHouseStagingPath(clickhouseStagingFile));
      }
    }
  }

  /** The local archive path from the most recent run. Exists for tests. */
  @Nullable
  Path lastLocalArchive() {
    return lastLocalArchive;
  }

  // ---------------------------------------------------------------------------
  // Postgres
  // ---------------------------------------------------------------------------

  /**
   * Dumps every database as the superuser. The four databases have three
   * different owners — {@code langfuse} belongs to the superuser, {@code dtrack}
   * to {@code dtrack}, and the two Temporal databases to {@code temporal} — so
   * only the superuser can read them all.
   */
  private List<String> dumpCommand(final String database) {
    List<String> command = new ArrayList<>(dockerExecPostgres());
    command.addAll(List.of("pg_dump", "--no-password", "-U", postgresUser(),
        "-d", database));
    return command;
  }

  /**
   * Roles are dumped separately from the databases, so a restore can create absent
   * roles without disturbing existing ones — the {@code *-db-init} compose
   * services normally own them.
   */
  private List<String> rolesDumpCommand() {
    List<String> command = new ArrayList<>(dockerExecPostgres());
    command.addAll(List.of("pg_dumpall", "--no-password", "-U", postgresUser(),
        "--roles-only"));
    return command;
  }

  /**
   * {@code -e PGPASSWORD} with a <em>bare name and no value</em>: the Docker CLI
   * forwards the value from its own environment, which we set on the child process
   * below. The password therefore appears in no process's argv and cannot be read
   * from {@code ps} on the host.
   */
  private List<String> dockerExecPostgres() {
    return List.of(properties.dockerBinary(), "exec", "-e", "PGPASSWORD",
        properties.postgresContainer());
  }

  private Map<String, String> postgresEnvironment() {
    String password = environment.apply("LANGFUSE_DB_PASSWORD");
    return password == null ? Map.of() : Map.of("PGPASSWORD", password);
  }

  private String postgresUser() {
    String user = environment.apply("LANGFUSE_DB_USER");
    return user == null || user.isBlank() ? "postgres" : user;
  }

  // ---------------------------------------------------------------------------
  // ClickHouse
  // ---------------------------------------------------------------------------

  /**
   * Lets ClickHouse serialise its own state, then folds the result into the
   * archive verbatim.
   *
   * <p>Deliberately not a hand-rolled per-table export: Langfuse's ClickHouse
   * schema has materialized views whose data lives in {@code .inner_id.*} tables,
   * and the schema moves with Langfuse versions. A blind spot there yields a
   * backup that restores looking correct and is quietly missing a view.
   */
  private PlatformManifest.ClickHouseSection captureClickHouse(
      final ZipOutputStream zos, final String stagingFileName)
      throws CommandRunner.CommandFailedException, IOException {

    // Counted before the BACKUP so the manifest describes the same snapshot the
    // archive was taken from, rather than one that drifted during a long dump.
    final Map<String, Long> tables = readClickHouseRowCounts();

    // Names the file by ClickHouse's view of the shared volume, not ours: it is
    // the process doing the writing, and the two mount paths differ.
    commands.runCapturingOutput(clickhouseQuery(
        "BACKUP DATABASE " + properties.clickhouseDatabase()
            + " TO File('" + stagingFileName + "')"), Map.of());

    Path staged = clickHouseStagingPath(stagingFileName);
    if (!Files.exists(staged)) {
      throw new IOException("ClickHouse reported a successful backup but produced no "
          + "file at " + staged + " — check that the langfuse-clickhouse-backups "
          + "volume is mounted into both containers and writable by uid 101");
    }

    String entry = CLICKHOUSE_ENTRY_PREFIX + properties.clickhouseDatabase() + ".zip";
    zos.putNextEntry(new ZipEntry(entry));
    long bytes = Files.copy(staged, zos);
    zos.closeEntry();

    return new PlatformManifest.ClickHouseSection(
        properties.clickhouseContainer(), properties.clickhouseDatabase(),
        entry, bytes, tables);
  }

  /**
   * Per-table row counts, from active parts. The only practical way to verify a
   * ClickHouse restore landed everything, because the archive itself is opaque.
   *
   * <p>Materialized-view inner tables ({@code .inner_id.<uuid>}) are excluded
   * deliberately. Verified against the pinned {@code 26.7.1.1315} image: a
   * restore regenerates those UUIDs, so recording them would make every manifest
   * incomparable with the next and would show as a "missing table" on a
   * post-restore check that actually succeeded. Their data is derived from the
   * base tables and is restored with them.
   */
  private Map<String, Long> readClickHouseRowCounts()
      throws CommandRunner.CommandFailedException {
    String output = commands.runCapturingOutput(clickhouseQuery(
        "SELECT table, sum(rows) FROM system.parts WHERE active AND database = '"
            + properties.clickhouseDatabase() + "' AND table NOT LIKE '.inner%' "
            + "GROUP BY table ORDER BY table"),
        Map.of());
    return parseRowCounts(output);
  }

  private static Map<String, Long> parseRowCounts(final String output) {
    Map<String, Long> tables = new LinkedHashMap<>();
    for (String line : output.split("\n")) {
      String[] parts = line.split("\t");
      if (parts.length == 2) {
        try {
          tables.put(parts[0].trim(), Long.parseLong(parts[1].trim()));
        } catch (NumberFormatException ex) {
          LOG.warn("Unparseable ClickHouse row count line: {}", line);
        }
      }
    }
    return tables;
  }

  /**
   * Builds a {@code clickhouse-client} invocation that runs inside the ClickHouse
   * container.
   *
   * <p>Credentials come from the ClickHouse container's <em>own</em> environment,
   * which {@code docker-compose.prod.yml} already populates with
   * {@code CLICKHOUSE_USER} and {@code CLICKHOUSE_PASSWORD}. Expanding them in a
   * shell inside the container keeps the password out of every argv — ours and
   * the container's — without depending on whether this particular
   * {@code clickhouse-client} build reads those variables itself.
   *
   * <p>The SQL is passed as a separate argument and referenced as {@code "$1"},
   * not interpolated into the shell string. That matters: the queries here embed
   * single quotes (<code>database = 'default'</code>), and inlining them into an
   * {@code sh -c} string is how quoting bugs turn into either a syntax error or,
   * worse, a silently different query.
   */
  private List<String> clickhouseQuery(final String sql) {
    return List.of(
        properties.dockerBinary(), "exec", properties.clickhouseContainer(),
        "sh", "-c",
        "exec clickhouse-client --user \"${CLICKHOUSE_USER:-clickhouse}\" "
            + "--password \"${CLICKHOUSE_PASSWORD:-}\" --query \"$1\"",
        "clickhouse-query", sql);
  }

  private Path clickHouseStagingPath(final String fileName) {
    return Path.of(properties.clickhouseBackupPath()).resolve(fileName);
  }

  /**
   * Deletes leftovers from crashed prior runs. Failures are logged, not fatal: a
   * stale file we cannot remove is a disk-space problem, not a reason to skip
   * tonight's backup.
   */
  private void sweepClickHouseOrphans() {
    Path dir = Path.of(properties.clickhouseBackupPath());
    if (!Files.isDirectory(dir)) {
      LOG.debug("ClickHouse backup directory {} absent; nothing to sweep", dir);
      return;
    }
    try (Stream<Path> entries = Files.list(dir)) {
      List<Path> orphans = entries
          .filter(path -> path.getFileName().toString().startsWith(CLICKHOUSE_STAGING_PREFIX))
          .toList();
      for (Path orphan : orphans) {
        LOG.warn("Sweeping orphaned ClickHouse backup file from a previous run: {}", orphan);
        deleteQuietly(orphan);
      }
    } catch (IOException ex) {
      LOG.warn("Could not sweep ClickHouse backup directory {}: {}", dir, ex.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Shared
  // ---------------------------------------------------------------------------

  /**
   * Streams one command's stdout into a zip entry, returning its size.
   *
   * <p>Streaming rather than buffering: dump sizes here are unbounded and this
   * container is capped at 2 GB. The byte count the manifest needs falls out of
   * the copy for free.
   */
  private PlatformManifest.DumpEntry writeEntry(final ZipOutputStream zos,
      final String entryName, final List<String> command)
      throws CommandRunner.CommandFailedException, IOException {
    zos.putNextEntry(new ZipEntry(entryName));
    long bytes = commands.runToStream(command, postgresEnvironment(), zos);
    zos.closeEntry();
    if (bytes == 0) {
      // Recorded honestly rather than suppressed — the operator needs to see it.
      LOG.warn("Dump for {} produced zero bytes", entryName);
    }
    return new PlatformManifest.DumpEntry(entryName, bytes);
  }

  /**
   * Records the image tag of each tool at capture time. A {@code dtrack} dump
   * taken under Dependency-Track 5.0.3 and restored into a later major may need
   * that version's own schema migration afterwards, and this is what tells you
   * which version produced the dump.
   *
   * <p>Best-effort: a missing container costs an entry, not the backup.
   */
  private Map<String, String> readImageTags() {
    Map<String, String> images = new LinkedHashMap<>();
    for (String container : properties.imageContainers()) {
      try {
        images.put(container, commands.runCapturingOutput(List.of(
            properties.dockerBinary(), "inspect", "--format", "{{.Config.Image}}",
            container), Map.of()));
      } catch (CommandRunner.CommandFailedException ex) {
        LOG.warn("Could not read image tag for {}: {}", container, ex.getMessage());
      }
    }
    return images;
  }

  private static int uploadProgressPercent(final long sent, final long total) {
    if (total <= 0) {
      return 75;
    }
    return Math.min(95, 75 + (int) ((sent * 20L) / total));
  }

  private static String summarise(
      final Map<String, PlatformManifest.DumpEntry> databases,
      final PlatformManifest.DumpEntry roles,
      final PlatformManifest.ClickHouseSection clickhouse,
      final long archiveSize) {
    long postgresBytes = databases.values().stream()
        .mapToLong(PlatformManifest.DumpEntry::bytes).sum() + roles.bytes();
    long clickhouseRows = clickhouse.tables().values().stream()
        .mapToLong(Long::longValue).sum();
    return String.format(
        "%d databases (%s of SQL), ClickHouse %s across %d tables (%d rows); "
            + "archive %s",
        databases.size(), BackupMetadata.formatFileSize(postgresBytes),
        BackupMetadata.formatFileSize(clickhouse.bytes()), clickhouse.tables().size(),
        clickhouseRows, BackupMetadata.formatFileSize(archiveSize));
  }

  /**
   * Creates the staging archive in the temp directory with owner-only permissions.
   *
   * <p>The shared temp directory is world-writable, and this archive is not
   * ordinary scratch data: it contains complete database dumps plus
   * {@code pg_dumpall --roles-only} output, which carries role password hashes.
   * Creating it with the permissions applied <em>at creation</em> — rather than
   * setting them afterwards — leaves no window in which the file exists readable.
   *
   * <p>Falls back to a plain temp file on filesystems without POSIX permissions.
   * That is not a silent downgrade in practice: this runs in a Linux container,
   * and the fallback exists so a developer on a non-POSIX filesystem is not
   * blocked outright.
   */
  private static Path createOwnerOnlyTempFile() throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return Files.createTempFile("platform-backup-", ".zip",
          PosixFilePermissions.asFileAttribute(
              PosixFilePermissions.fromString("rw-------")));
    }
    LOG.warn("Filesystem has no POSIX permission support; the staging archive "
        + "will use default permissions");
    return Files.createTempFile("platform-backup-", ".zip");
  }

  private static void deleteQuietly(@Nullable final Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      LOG.warn("Failed to delete {}: {}", path, ex.getMessage());
    }
  }
}
