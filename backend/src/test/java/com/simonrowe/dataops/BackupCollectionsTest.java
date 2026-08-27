package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Collection;
import org.junit.jupiter.api.Test;

/**
 * Guards a mistake this repository has made twice: adding a collection holding paid-for
 * generated content and forgetting the backup lists, so a restore silently discards it.
 */
class BackupCollectionsTest {

  private static final String PLATFORM_RELEASES = "platform_releases";

  @SuppressWarnings("unchecked")
  private static Collection<String> readList(final Class<?> type, final String fieldName)
      throws ReflectiveOperationException {
    Field field = type.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Collection<String>) field.get(null);
  }

  @Test
  void platformReleasesAreBackedUp() throws ReflectiveOperationException {
    assertThat(readList(BackupService.class, "BACKUP_COLLECTIONS"))
        .contains(PLATFORM_RELEASES);
  }

  @Test
  void platformReleasesAreRestored() throws ReflectiveOperationException {
    assertThat(readList(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"))
        .contains(PLATFORM_RELEASES);
  }

  @Test
  void everyGeneratedContentCollectionIsBackedUp() throws ReflectiveOperationException {
    // article_summaries and narrations each cost money to produce; so does platform_releases.
    assertThat(readList(BackupService.class, "BACKUP_COLLECTIONS"))
        .contains("article_summaries", "narrations", PLATFORM_RELEASES);
  }
}
