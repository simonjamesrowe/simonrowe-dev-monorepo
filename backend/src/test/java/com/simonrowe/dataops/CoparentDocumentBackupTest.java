package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.embedding.ElasticsearchBackupService;
import com.simonrowe.narration.NarrationRestoreValidator;
import com.simonrowe.search.IndexService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Document files go into the archive and come back out, and cannot escape the store. */
class CoparentDocumentBackupTest {

  @TempDir
  private Path temp;

  @Test
  void documentFilesRoundTripThroughTheBackupArchive() throws IOException {
    final Path source = temp.resolve("source");
    Files.createDirectories(source.resolve("family1"));
    Files.write(source.resolve("family1").resolve("file1"), new byte[] {4, 5, 6});
    final Path archive = temp.resolve("backup.zip");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archive))) {
      BackupService.exportDirectory(zos, source, "coparent-documents/");
    }

    final Path restored = temp.resolve("restored");
    restoreService(restored).restoreCoparentDocuments(archive);

    assertThat(restored.resolve("family1").resolve("file1"))
        .hasBinaryContent(new byte[] {4, 5, 6});
    assertThat(temp.resolve("receipts")).as("receipts are a different prefix").doesNotExist();
  }

  @Test
  void anEntryThatClimbsOutOfTheStoreIsSkipped() throws IOException {
    final Path archive = temp.resolve("hostile.zip");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archive))) {
      zos.putNextEntry(new ZipEntry("coparent-documents/../../escaped"));
      zos.write("x".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }

    final Path store = temp.resolve("store").resolve("documents");
    restoreService(store).restoreCoparentDocuments(archive);

    assertThat(temp.resolve("escaped")).doesNotExist();
    assertThat(temp.resolve("store").resolve("escaped")).doesNotExist();
  }

  private RestoreService restoreService(final Path documents) {
    return new RestoreService(mock(MongoTemplate.class), mock(MongoTemplate.class),
        mock(GoogleDriveService.class), mock(DataOperationsService.class),
        mock(BackupService.class), mock(IndexService.class),
        mock(ElasticsearchBackupService.class), mock(NarrationRestoreValidator.class),
        temp.resolve("uploads").toString(), temp.resolve("school").toString(),
        temp.resolve("receipts").toString(), documents.toString(),
        temp.resolve("meal-images").toString());
  }
}
