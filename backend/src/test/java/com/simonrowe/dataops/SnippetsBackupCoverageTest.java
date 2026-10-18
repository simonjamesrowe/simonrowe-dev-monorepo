package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V061CreateSnippets;
import com.simonrowe.snippet.Snippet;
import com.simonrowe.snippet.SnippetRepository;
import com.simonrowe.snippet.SnippetSourceType;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Snippets are hand-written notes on passages that a regenerated summary may no longer
 * contain, so nothing re-derives them: they have to survive a backup and restore.
 */
class SnippetsBackupCoverageTest extends AbstractIntegrationTest {

  @Autowired private BackupService backupService;
  @Autowired private RestoreService restoreService;
  @Autowired private SnippetRepository snippetRepository;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  @AfterEach
  void clean() {
    snippetRepository.deleteAll();
  }

  @SuppressWarnings("unchecked")
  private static Collection<String> constant(final Class<?> type, final String fieldName)
      throws Exception {
    Field field = type.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Collection<String>) field.get(null);
  }

  @Test
  void snippetsAreBackedUpAndRestoredButNeverCleared() throws Exception {
    List<String> restore = new ArrayList<>();
    restore.addAll(constant(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"));
    restore.addAll(constant(RestoreService.class, "IMPORT_ORDER_DEPENDENT"));

    assertThat(constant(BackupService.class, "BACKUP_COLLECTIONS")).contains(Snippet.COLLECTION);
    assertThat(restore).contains(Snippet.COLLECTION);
    assertThat(constant(ClearService.class, "COLLECTIONS")).doesNotContain(Snippet.COLLECTION);
  }

  @Test
  void theArchiveHoldsTheSnippet() throws IOException {
    snippetRepository.save(snippet());

    Path archive = backupService.createLocalBackup();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      ZipEntry records = zip.getEntry("collections/" + Snippet.COLLECTION + ".json");
      assertThat(records).isNotNull();
      assertThat(new String(zip.getInputStream(records).readAllBytes(), StandardCharsets.UTF_8))
          .contains("review what it is required to do", "opening slide", "platform-eng-talk");
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  /**
   * A restore drops the collection with its indexes, and V061 is already recorded as run, so
   * only the restore can put them back.
   */
  @Test
  void restoreRecreatesTheIndexes() {
    snippetRepository.save(snippet());
    mongoTemplate.getCollection(Snippet.COLLECTION).drop();

    restoreService.ensureSnippetIndexes();

    List<String> names = mongoTemplate.getCollection(Snippet.COLLECTION).listIndexes()
        .map(index -> index.getString("name"))
        .into(new ArrayList<>());
    assertThat(names).containsAll(V061CreateSnippets.INDEXES);
  }

  private static Snippet snippet() {
    Instant now = Instant.now();
    return new Snippet(null, SnippetSourceType.ARTICLE_SUMMARY, "article-1", "summary-1",
        "review what it is required to do", "", "", "opening slide",
        List.of("platform-eng-talk"), "Human PR Review Is Next", "TLDR Dev", null,
        "https://example.com/pr", now, "owner", now, now);
  }
}
