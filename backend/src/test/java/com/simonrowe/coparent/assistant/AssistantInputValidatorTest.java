package com.simonrowe.coparent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class AssistantInputValidatorTest {

  private final AssistantInputValidator validator = new AssistantInputValidator();

  @Test
  void acceptsTextAndMatchingPngBytes() {
    final byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    final MockMultipartFile image = new MockMultipartFile(
        "image", "flyer.png", "image/png", png);

    final AssistantInputValidator.ValidatedInput result = validator.validate("  School fair  ",
        image);

    assertThat(result.text()).isEqualTo("School fair");
    assertThat(result.imageContentType()).isEqualTo("image/png");
    assertThat(result.imageBytes()).containsExactly(png);
  }

  @Test
  void rejectsMissingInputAndOversizedText() {
    assertThatThrownBy(() -> validator.validate(" ", null))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> validator.validate("x".repeat(20_001), null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rejectsUnsupportedOrSpoofedImageTypes() {
    final MockMultipartFile gif = new MockMultipartFile(
        "image", "photo.gif", "image/gif", new byte[] {0x47, 0x49, 0x46});
    final MockMultipartFile spoofed = new MockMultipartFile(
        "image", "photo.png", "image/png", new byte[] {0x47, 0x49, 0x46});

    assertThatThrownBy(() -> validator.validate(null, gif))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> validator.validate(null, spoofed))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void comparesImageContentWithoutExposingTransientInputInLogs() {
    final AssistantInputValidator.ValidatedInput first =
        new AssistantInputValidator.ValidatedInput(
            "private note", "image/png", new byte[] {1, 2, 3});
    final AssistantInputValidator.ValidatedInput same =
        new AssistantInputValidator.ValidatedInput(
            "private note", "image/png", new byte[] {1, 2, 3});
    final AssistantInputValidator.ValidatedInput different =
        new AssistantInputValidator.ValidatedInput(
            "private note", "image/png", new byte[] {1, 2, 4});

    assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(different);
    assertThat(first.toString())
        .contains("textPresent=true", "imageByteLength=3")
        .doesNotContain("private note", "[1, 2, 3]");
  }
}
