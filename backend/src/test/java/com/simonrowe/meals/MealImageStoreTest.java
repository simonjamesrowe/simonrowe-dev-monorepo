package com.simonrowe.meals;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MealImageStoreTest {

  @TempDir
  private Path temp;

  @Test
  void storesByContentHashAndReadsBack() {
    MealImageStore store = new MealImageStore(temp.toString());

    String name = store.store(MealBundles.PNG).orElseThrow();

    assertThat(name).matches("[0-9a-f]{64}\\.png");
    assertThat(store.store(MealBundles.PNG)).contains(name);
    assertThat(store.read(name)).hasValueSatisfying(b -> assertThat(b).isEqualTo(MealBundles.PNG));
  }

  @Test
  void sniffsTheTypeFromTheBytes() {
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);

    assertThat(MealImageStore.sniff(jpeg)).isEqualTo("jpg");
    assertThat(MealImageStore.sniff(webp)).isEqualTo("webp");
    assertThat(MealImageStore.sniff("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)))
        .isNull();
  }

  @Test
  void refusesAnythingThatIsNotAnImage() {
    MealImageStore store = new MealImageStore(temp.toString());

    assertThat(store.store("<html></html> padding".getBytes(StandardCharsets.UTF_8))).isEmpty();
  }

  @Test
  void readsOnlyWellFormedNamesInsideTheStore() throws Exception {
    Files.writeString(temp.resolve("secret.txt"), "no");
    MealImageStore store = new MealImageStore(temp.resolve("images").toString());

    assertThat(store.read("../secret.txt")).isEmpty();
    assertThat(store.read("a".repeat(64) + ".jpg")).isEmpty();
    assertThat(store.read(null)).isEmpty();
  }
}
