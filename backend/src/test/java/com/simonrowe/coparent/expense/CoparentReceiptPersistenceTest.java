package com.simonrowe.coparent.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins expense receipts to their own volume in production. Without it the files live in the
 * backend container's writable layer, and {@code backend} is recreated by every deploy, so every
 * deploy would delete every receipt while the expense rows went on pointing at them.
 */
class CoparentReceiptPersistenceTest {

  private static final Path COMPOSE = Path.of("..", "docker-compose.prod.yml");
  private static final String MOUNT_POINT = "/workspace/coparent-receipts";

  @Test
  void receiptsAreOnNamedVolumeAtTheConfiguredAbsolutePath() throws IOException {
    final List<String> backend = serviceBlock("backend");

    assertThat(backend).anySatisfy(line -> assertThat(line.trim())
        .isEqualTo("- coparent-receipts:" + MOUNT_POINT));
    assertThat(backend).anySatisfy(line -> assertThat(line.trim())
        .isEqualTo("COPARENT_RECEIPT_PATH: " + MOUNT_POINT + "/"));
    assertThat(Files.readString(COMPOSE)).containsPattern("(?m)^  coparent-receipts:\\s*$");
  }

  @Test
  void receiptsAreNotUnderTheUnauthenticatedUploadsPath() {
    assertThat(MOUNT_POINT).doesNotStartWith("/workspace/uploads");
  }

  private List<String> serviceBlock(final String service) throws IOException {
    final List<String> lines = Files.readAllLines(COMPOSE);
    final int start = lines.indexOf("  " + service + ":");
    assertThat(start).as("no `%s` service in the compose file", service).isNotNegative();
    int end = start + 1;
    while (end < lines.size() && !lines.get(end).matches("^ {2}\\S.*")) {
      end++;
    }
    return lines.subList(start, end);
  }
}
