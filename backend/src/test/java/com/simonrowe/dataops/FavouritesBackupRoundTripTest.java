package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Proves favourites survive a backup, and that the restore path puts their
 * indexes back.
 *
 * <p>Favourites were absent from the backup set entirely, so the digest's only
 * input was unprotected. Adding them introduced a second hazard: the restore
 * drops each collection before re-inserting, and {@code dropCollection} takes
 * the indexes with it — including the unique index that stops the same article
 * being favourited twice, which was created by a change unit Mongock has
 * already marked executed and will therefore never recreate.
 */
class FavouritesBackupRoundTripTest extends AbstractIntegrationTest {

  private static final String COLLECTION = "favourites";

  @Autowired private BackupService backupService;
  @Autowired private RestoreService restoreService;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  @AfterEach
  void cleanCollection() {
    mongoTemplate.dropCollection(COLLECTION);
  }

  @Test
  void backupArchiveContainsFavourites() throws IOException {
    mongoTemplate.insert(new Favourite(
        null, FavouriteType.NEWS, "article-42", Instant.now()), COLLECTION);

    Path archive = backupService.createLocalBackup();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      var entry = zip.getEntry("collections/favourites.json");
      assertThat(entry)
          .as("favourites must be exported into the backup archive")
          .isNotNull();

      String json = new String(
          zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
      assertThat(json).contains("article-42");
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  @Test
  void restoreRecreatesTheFavouriteIndexes() {
    // Simulates the state right after the restore's dropCollection + insert:
    // documents present, indexes gone.
    mongoTemplate.insert(new Favourite(
        null, FavouriteType.NEWS, "article-1", Instant.now()), COLLECTION);
    assertThat(indexNames()).doesNotContain("idx_type_content");

    restoreService.ensureFavouriteIndexes();

    assertThat(indexNames())
        .contains("idx_type_content", "idx_type_created");
  }

  @Test
  void theRecreatedUniqueIndexStillRejectsDuplicateFavourites() {
    mongoTemplate.insert(new Favourite(
        null, FavouriteType.NEWS, "article-1", Instant.now()), COLLECTION);
    restoreService.ensureFavouriteIndexes();

    assertThatThrownBy(() -> mongoTemplate.insert(new Favourite(
        null, FavouriteType.NEWS, "article-1", Instant.now()), COLLECTION))
        .isInstanceOf(DuplicateKeyException.class);
  }

  private List<String> indexNames() {
    return mongoTemplate.indexOps(COLLECTION).getIndexInfo().stream()
        .map(index -> index.getName())
        .toList();
  }
}
