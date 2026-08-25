package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryRepository;
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
 * Generated summaries cost an LLM call each, so they have to survive a backup/restore
 * round trip. This is the same coverage {@code NarrationBackupCoverageTest} gives audio.
 */
class ArticleSummaryBackupCoverageTest extends AbstractIntegrationTest {

  private static final String COLLECTION = "article_summaries";

  @Autowired private BackupService backupService;
  @Autowired private RestoreService restoreService;
  @Autowired private ArticleSummaryRepository summaryRepository;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  @AfterEach
  void clean() {
    summaryRepository.deleteAll();
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
  void articleSummariesAreBackedUpAndRestored() throws Exception {
    Collection<String> backup = constant(BackupService.class, "BACKUP_COLLECTIONS");
    List<String> restore = new ArrayList<>();
    restore.addAll(constant(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"));
    restore.addAll(constant(RestoreService.class, "IMPORT_ORDER_DEPENDENT"));

    assertThat(backup).contains(COLLECTION);
    assertThat(restore).contains(COLLECTION);
  }

  /**
   * Deliberately absent from {@code ClearService}: its parent content,
   * {@code aggregated_articles}, is not cleared either, so clearing derived summaries
   * would strand the feature against articles that are still present.
   */
  @Test
  void articleSummariesAreNotClearedBecauseTheirParentArticlesAreNot() throws Exception {
    Collection<String> clear = constant(ClearService.class, "COLLECTIONS");

    assertThat(clear).doesNotContain(COLLECTION, "aggregated_articles");
  }

  @Test
  void localArchiveContainsTheSummaryRecord() throws IOException {
    summaryRepository.save(readySummary());

    Path archive = backupService.createLocalBackup();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      ZipEntry records = zip.getEntry("collections/" + COLLECTION + ".json");
      assertThat(records).isNotNull();
      assertThat(new String(zip.getInputStream(records).readAllBytes(),
          StandardCharsets.UTF_8))
          .contains("article-1", "Generated prose about the article.");
      // No manifest assertion here: createLocalBackup is the pre-restore safety copy and
      // deliberately writes collections and uploads only. The manifest's
      // articleSummaryCount belongs to the full createBackup path.
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  /**
   * A restore drops the collection, taking its indexes with it, and {@code V020} has
   * already been recorded as executed — so unless the restore recreates them the indexes
   * are gone for good.
   */
  @Test
  void restoreRecreatesTheIndexesThatDropCollectionRemoved() {
    summaryRepository.save(readySummary());
    mongoTemplate.getCollection(COLLECTION).drop();

    restoreService.ensureArticleSummaryIndexes();

    List<String> names = mongoTemplate.getCollection(COLLECTION).listIndexes()
        .map(index -> index.getString("name"))
        .into(new ArrayList<>());
    assertThat(names).contains(
        "idx_article_summary_article", "idx_article_summary_status_article");
  }

  private static ArticleSummary readySummary() {
    ArticleSummary summary = new ArticleSummary(
        "summary-1", "article-1", Instant.now());
    summary.markReady("Generated prose about the article.", "test-model", 4200,
        Instant.now());
    return summary;
  }
}
