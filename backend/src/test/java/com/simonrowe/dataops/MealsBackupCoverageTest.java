package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.embedding.ElasticsearchBackupService;
import com.simonrowe.narration.NarrationRestoreValidator;
import com.simonrowe.search.IndexService;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Everything the meals app stores comes back from a backup (spec 053, "Backup and restore").
 *
 * <p>The collections are discovered from the {@code @Document} classes in
 * {@code com.simonrowe.meals}, not listed here, so a collection added later that nobody put
 * in the backup fails this test rather than going quietly unbacked.
 */
class MealsBackupCoverageTest {

  @TempDir
  private Path temp;

  @Test
  void everyMealsCollectionIsBackedUpAndRestored() throws Exception {
    List<String> collections = mealsCollections();

    assertThat(collections).contains("meal_recipes", "meal_ratings");
    assertThat(constant(BackupService.class, "BACKUP_COLLECTIONS"))
        .containsAll(collections);
    assertThat(constant(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"))
        .containsAll(collections);
  }

  @Test
  void recipePhotosRoundTripThroughTheArchive() throws IOException {
    Path source = temp.resolve("source");
    Files.createDirectories(source);
    Files.write(source.resolve("abc.png"), new byte[] {1, 2, 3});
    Path archive = temp.resolve("backup.zip");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archive))) {
      BackupService.exportDirectory(zos, source, "meal-images/");
    }

    Path restored = temp.resolve("restored");
    restoreService(restored).restoreMealImages(archive);

    assertThat(restored.resolve("abc.png")).hasBinaryContent(new byte[] {1, 2, 3});
  }

  @Test
  void photoEntryCannotClimbOutOfTheStore() throws IOException {
    Path archive = temp.resolve("hostile.zip");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archive))) {
      zos.putNextEntry(new ZipEntry("meal-images/../../escaped"));
      zos.write("x".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }

    restoreService(temp.resolve("store").resolve("images")).restoreMealImages(archive);

    assertThat(temp.resolve("escaped")).doesNotExist();
    assertThat(temp.resolve("store").resolve("escaped")).doesNotExist();
  }

  private static List<String> mealsCollections() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
    List<String> names = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents("com.simonrowe.meals")) {
      Class<?> type = Class.forName(definition.getBeanClassName());
      names.add(type.getAnnotation(Document.class).collection());
    }
    return names;
  }

  @SuppressWarnings("unchecked")
  private static Collection<String> constant(final Class<?> type, final String name)
      throws ReflectiveOperationException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return (Collection<String>) field.get(null);
  }

  private RestoreService restoreService(final Path images) {
    return new RestoreService(mock(MongoTemplate.class), mock(MongoTemplate.class),
        mock(GoogleDriveService.class), mock(DataOperationsService.class),
        mock(BackupService.class), mock(IndexService.class),
        mock(ElasticsearchBackupService.class), mock(NarrationRestoreValidator.class),
        temp.resolve("uploads").toString(), temp.resolve("school").toString(),
        temp.resolve("receipts").toString(), images.toString());
  }
}
