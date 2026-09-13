package com.simonrowe.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumePhotoProviderTest {

  @TempDir
  private Path uploads;

  @Test
  void readsTheCmsSelectedPhotoFromTheUploadsDirectory() throws IOException {
    Files.createDirectories(uploads.resolve("abc123"));
    Files.write(uploads.resolve("abc123/headshot.jpg"), new byte[]{1, 2, 3});
    ResumePhotoProvider provider = new ResumePhotoProvider(uploads.toString());

    Optional<byte[]> photo = provider.resolve("/uploads/abc123/headshot.jpg");

    assertThat(photo).isPresent();
    assertThat(photo.get()).containsExactly(1, 2, 3);
  }

  @Test
  void fallsBackToTheBundledHeadshotWhenNoPhotoIsSelected() {
    ResumePhotoProvider provider = new ResumePhotoProvider(uploads.toString());

    Optional<byte[]> photo = provider.resolve(null);

    // The bundled default is what makes the CV ship working before anyone has uploaded
    // anything through the admin CMS.
    assertThat(photo).isPresent();
    assertThat(photo.get()).isNotEmpty();
  }

  @Test
  void fallsBackToTheBundledHeadshotWhenTheSelectedPhotoIsMissing() {
    ResumePhotoProvider provider = new ResumePhotoProvider(uploads.toString());

    Optional<byte[]> photo = provider.resolve("/uploads/gone/headshot.jpg");

    assertThat(photo).isPresent();
  }

  @Test
  void refusesPathsEscapingTheUploadsDirectory() throws IOException {
    Path secret = uploads.getParent().resolve("secret.jpg");
    Files.write(secret, new byte[]{9, 9, 9});
    ResumePhotoProvider provider = new ResumePhotoProvider(uploads.toString());

    Optional<byte[]> photo = provider.resolve("/uploads/../secret.jpg");

    // The URL is CMS data, so traversal has to be refused on the resolved path rather
    // than trusted to the prefix check alone.
    assertThat(photo).isPresent();
    assertThat(photo.get()).isNotEqualTo(new byte[]{9, 9, 9});
  }

  @Test
  void refusesAbsoluteUrlsOutsideTheUploadsPrefix() {
    ResumePhotoProvider provider = new ResumePhotoProvider(uploads.toString());

    Optional<byte[]> photo = provider.resolve("https://example.com/photo.jpg");

    assertThat(photo).isPresent();
    assertThat(photo.get()).isNotEmpty();
  }
}
