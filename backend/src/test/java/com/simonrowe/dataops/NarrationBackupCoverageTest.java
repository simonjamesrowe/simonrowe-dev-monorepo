package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mongodb.client.MongoClient;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.embedding.ElasticsearchBackupService;
import com.simonrowe.narration.Narration;
import com.simonrowe.narration.NarrationRepository;
import com.simonrowe.narration.NarrationRestoreValidator;
import com.simonrowe.narration.NarrationStorage;
import com.simonrowe.search.IndexService;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties =
    "uploads.path=target/test-narration-backup-uploads")
class NarrationBackupCoverageTest extends AbstractIntegrationTest {

  private static final Path UPLOADS = Path.of("target/test-narration-backup-uploads");
  private static final byte[] MP3 = new byte[]{'I', 'D', '3', 4, 5, 6, 7, 8};

  @Autowired private BackupService backupService;
  @Autowired private NarrationRepository narrationRepository;
  @Autowired private NarrationStorage narrationStorage;

  @BeforeEach
  @AfterEach
  void clean() throws IOException {
    narrationRepository.deleteAll();
    if (Files.exists(UPLOADS)) {
      try (var paths = Files.walk(UPLOADS)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Collection<String> constant(
      final Class<?> type, final String fieldName
  ) throws Exception {
    Field field = type.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Collection<String>) field.get(null);
  }

  @Test
  void narrationIsBackedUpRestoredAfterBlogsAndCleared() throws Exception {
    Collection<String> backup = constant(BackupService.class, "BACKUP_COLLECTIONS");
    List<String> restore = new ArrayList<>();
    restore.addAll(constant(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"));
    restore.addAll(constant(RestoreService.class, "IMPORT_ORDER_DEPENDENT"));
    Collection<String> clear = constant(ClearService.class, "COLLECTIONS");

    assertThat(backup).contains("narrations");
    assertThat(restore).contains("narrations");
    assertThat(restore.indexOf("narrations")).isGreaterThan(restore.indexOf("blogs"));
    assertThat(clear).contains("narrations");
  }

  @Test
  void localArchiveContainsNarrationRecordAndAudio() throws IOException {
    Narration narration = new Narration(
        "narration-1", "blog-1", 100, "voice", "en-GB", "MP3",
        "narrations/narration-1.mp3", Instant.now());
    narration.markReady(narrationStorage.store("narration-1", MP3), Instant.now());
    narrationRepository.save(narration);

    Path archive = backupService.createLocalBackup();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      ZipEntry records = zip.getEntry("collections/narrations.json");
      assertThat(records).isNotNull();
      assertThat(new String(zip.getInputStream(records).readAllBytes(),
          StandardCharsets.UTF_8)).contains("narration-1", "checksumSha256");
      assertThat(zip.getEntry(
          "uploads/narrations/narration-1/narration.mp3")).isNotNull();
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  @Test
  void olderArchiveWithoutNarrationCollectionClearsStaleLocalRecords()
      throws IOException {
    Path archive = Files.createTempFile("old-backup-", ".zip");
    try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry("collections/blogs.json"));
      output.write("[]".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
    MongoTemplate mongo = mock(MongoTemplate.class);
    RestoreService restore = new RestoreService(
        mongo, mock(GoogleDriveService.class), mock(DataOperationsService.class),
        mock(BackupService.class), mock(IndexService.class),
        mock(ElasticsearchBackupService.class), mock(NarrationRestoreValidator.class),
        UPLOADS.toString());

    restore.restoreCollections(archive);

    verify(mongo).dropCollection("narrations");
    Files.deleteIfExists(archive);
  }
}
