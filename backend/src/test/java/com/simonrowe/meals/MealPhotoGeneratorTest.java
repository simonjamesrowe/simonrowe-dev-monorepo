package com.simonrowe.meals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.web.server.ResponseStatusException;

/** The recipe photo generator, with the image model replaced by a canned PNG. */
class MealPhotoGeneratorTest {

  @TempDir
  Path temp;

  private final ImageModel imageModel = mock(ImageModel.class);

  @Test
  void storesTheGeneratedPhotoAsJpeg() throws IOException {
    MealImageStore store = new MealImageStore(temp.toString());
    when(imageModel.call(any())).thenReturn(response(Base64.getEncoder().encodeToString(png())));

    String name = new MealPhotoGenerator(imageModel, store)
        .generate("Lemon Pancakes", List.of("oats", "cottage cheese", "lemon"));

    assertThat(name).endsWith(".jpg");
    assertThat(Files.readAllBytes(temp.resolve(name))).startsWith((byte) 0xFF, (byte) 0xD8);
    ArgumentCaptor<ImagePrompt> prompt = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(imageModel).call(prompt.capture());
    assertThat(prompt.getValue().getInstructions().getFirst().getText())
        .contains("Lemon Pancakes").contains("oats, cottage cheese, lemon")
        .contains("No text");
    assertThat(((OpenAiImageOptions) prompt.getValue().getOptions()).getModel())
        .isEqualTo(MealPhotoGenerator.IMAGE_MODEL);
  }

  @Test
  void promptKeepsTheFirstDozenDistinctIngredients() {
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      many.add("thing" + i);
    }
    many.add(1, "thing0");
    many.add(2, " ");

    String prompt = MealPhotoGenerator.prompt("Stew", many);

    assertThat(prompt).contains("thing11").doesNotContain("thing12")
        .doesNotContain("thing0, thing0");
    assertThat(MealPhotoGenerator.prompt("Toast", null)).doesNotContain("Made with");
  }

  @Test
  void refusesNamelessDishWithoutCallingTheModel() {
    MealPhotoGenerator generator =
        new MealPhotoGenerator(imageModel, new MealImageStore(temp.toString()));

    assertThatThrownBy(() -> generator.generate(" ", List.of("egg")))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Name");
    verifyNoInteractions(imageModel);
  }

  @Test
  void anEmptyAnswerIsAnErrorNotAnEmptyPhoto() {
    when(imageModel.call(any())).thenReturn(response(null));

    assertThatThrownBy(() -> new MealPhotoGenerator(imageModel,
        new MealImageStore(temp.toString())).generate("Toast", List.of()))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("No photo");
  }

  @Test
  void unreadableBytesAreKeptAsTheyCame() {
    byte[] notAnImage = {1, 2, 3};

    assertThat(MealPhotoGenerator.asJpeg(notAnImage)).isSameAs(notAnImage);
  }

  private static ImageResponse response(final String b64) {
    return new ImageResponse(List.of(new ImageGeneration(new Image(null, b64))));
  }

  private static byte[] png() throws IOException {
    BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(3, 4, 0xFFAA5500);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }
}
