package com.simonrowe.meals;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins recipe photos to their own volume in production. Without it the files live in the
 * backend container's writable layer, which every deploy recreates, so every deploy would
 * delete every photo while the recipes went on naming them.
 */
class MealImagePersistenceTest {

  private static final Path COMPOSE = Path.of("..", "docker-compose.prod.yml");
  private static final String MOUNT_POINT = "/workspace/meal-images";

  @Test
  void photosAreOnTheirOwnNamedVolume() throws IOException {
    List<String> backend = serviceBlock("backend");

    assertThat(backend).anySatisfy(line -> assertThat(line.trim())
        .isEqualTo("- meal-images:" + MOUNT_POINT));
    assertThat(backend).anySatisfy(line -> assertThat(line.trim())
        .isEqualTo("MEALS_IMAGE_PATH: " + MOUNT_POINT + "/"));
    assertThat(Files.readString(COMPOSE)).containsPattern("(?m)^  meal-images:\\s*$");
  }

  @Test
  void photosAreNotUnderTheUnauthenticatedUploadsPath() {
    assertThat(MOUNT_POINT).doesNotStartWith("/workspace/uploads");
  }

  private List<String> serviceBlock(final String service) throws IOException {
    List<String> lines = Files.readAllLines(COMPOSE);
    int start = lines.indexOf("  " + service + ":");
    assertThat(start).as("no `%s` service in the compose file", service).isNotNegative();
    int end = start + 1;
    while (end < lines.size() && !lines.get(end).matches("^ {2}\\S.*")) {
      end++;
    }
    return lines.subList(start, end);
  }
}
