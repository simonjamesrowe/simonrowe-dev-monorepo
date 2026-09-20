package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Guards backup coverage and post-restore indexes for private CoParent records. */
class CoparentBackupCoverageTest extends AbstractIntegrationTest {

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.EVENTS,
      V043CreateCoparentCollections.CATEGORIES,
      V043CreateCoparentCollections.SCHEDULE_CHANGES,
      V043CreateCoparentCollections.CONVERSATIONS,
      V043CreateCoparentCollections.AUDITS);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @Autowired
  private RestoreService restoreService;

  @Autowired
  private BackupService backupService;

  @AfterEach
  void cleanCollections() {
    COLLECTIONS.forEach(mongoTemplate::dropCollection);
  }

  @Test
  void everyCoparentCollectionIsBackedUpAndRestored() throws ReflectiveOperationException {
    assertThat(collections(BackupService.class, "BACKUP_COLLECTIONS"))
        .doesNotContainAnyElementsOf(COLLECTIONS);
    assertThat(collections(BackupService.class, "COPARENT_BACKUP_COLLECTIONS"))
        .containsAll(COLLECTIONS);
    assertThat(collections(RestoreService.class, "COPARENT_IMPORT_ORDER"))
        .containsAll(COLLECTIONS);
  }

  @Test
  void backupWritesCoparentRecordsUnderTheirOwnDatabasePath() throws Exception {
    mongoTemplate.getCollection(V043CreateCoparentCollections.FAMILIES)
        .insertOne(new Document("_id", new ObjectId()).append("name", "Example"));

    final Path archive = backupService.createLocalBackup();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      assertThat(zip.getEntry("databases/coparent/collections/families.json")).isNotNull();
      assertThat(zip.getEntry("collections/families.json")).isNull();
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  @Test
  void restoreReadsCoparentRecordsIntoTheDedicatedDatabase() throws Exception {
    final ObjectId familyId = new ObjectId();
    final Path archive = Files.createTempFile("coparent-restore-", ".zip");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry("collections/blogs.json"));
      output.write("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
      output.putNextEntry(new ZipEntry("databases/coparent/collections/families.json"));
      output.write(("[{\"_id\":{\"$oid\":\"" + familyId.toHexString()
          + "\"},\"name\":\"Restored\"}]")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }

    try {
      restoreService.restoreCollections(archive);
      assertThat(mongoTemplate.getCollection(V043CreateCoparentCollections.FAMILIES)
          .countDocuments(new Document("_id", familyId))).isEqualTo(1);
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  @Test
  void restoreRecreatesAllCoparentIndexes() {
    restoreService.ensureCoparentIndexes();

    assertThat(indexNames(V043CreateCoparentCollections.PARENTS))
        .contains("idx_coparent_parent_subject", "idx_coparent_parent_family_subject");
    assertThat(indexNames(V043CreateCoparentCollections.INVITATIONS))
        .contains("idx_coparent_invitation_token");
    assertThat(indexNames(V043CreateCoparentCollections.CONVERSATIONS))
        .contains("idx_coparent_permission_id");
  }

  private List<String> indexNames(final String collection) {
    return mongoTemplate.indexOps(collection).getIndexInfo().stream()
        .map(index -> index.getName())
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static Collection<String> collections(
      final Class<?> type,
      final String fieldName) throws ReflectiveOperationException {
    final Field field = type.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Collection<String>) field.get(null);
  }
}
