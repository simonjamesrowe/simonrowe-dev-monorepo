package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards that the {@code favourites} collection stays inside the backup and
 * restore sets.
 *
 * <p>It was originally missing from both, which meant every favourite — the
 * only input the weekly digest has — sat outside the nightly backup entirely.
 * These assertions are deliberately reflective rather than behavioural: the
 * collection lists are private constants, and a regression here is someone
 * editing one list and not the other.
 */
class FavouritesBackupCoverageTest {

  private static final String FAVOURITES = "favourites";

  @SuppressWarnings("unchecked")
  private static Collection<String> constant(
      final Class<?> type, final String fieldName) throws Exception {
    Field field = type.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Collection<String>) field.get(null);
  }

  @Test
  void backupIncludesFavourites() throws Exception {
    assertThat(constant(BackupService.class, "BACKUP_COLLECTIONS"))
        .contains(FAVOURITES);
  }

  @Test
  void restoreIncludesFavourites() throws Exception {
    List<String> all = new ArrayList<>();
    all.addAll(constant(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"));
    all.addAll(constant(RestoreService.class, "IMPORT_ORDER_DEPENDENT"));

    assertThat(all).contains(FAVOURITES);
  }

  @Test
  void favouritesAreRestoredAfterTheContentTheyPointAt() throws Exception {
    List<String> all = new ArrayList<>();
    all.addAll(constant(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"));
    all.addAll(constant(RestoreService.class, "IMPORT_ORDER_DEPENDENT"));

    assertThat(all.indexOf(FAVOURITES))
        .isGreaterThan(all.indexOf("aggregated_articles"))
        .isGreaterThan(all.indexOf("aggregated_events"));
  }

  @Test
  void everyRestoredCollectionIsAlsoBackedUp() throws Exception {
    Collection<String> backed = constant(BackupService.class, "BACKUP_COLLECTIONS");
    List<String> restored = new ArrayList<>();
    restored.addAll(constant(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"));
    restored.addAll(constant(RestoreService.class, "IMPORT_ORDER_DEPENDENT"));

    assertThat(backed).containsAll(restored);
  }
}
