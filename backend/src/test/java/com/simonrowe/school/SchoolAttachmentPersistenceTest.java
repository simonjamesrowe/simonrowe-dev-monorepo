package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins school email attachments to a volume in production.
 *
 * <p>{@code school.attachment-path} defaults to the relative {@code school-attachments/}, which
 * in the backend's buildpack image resolves inside {@code /workspace} — the container's
 * <b>writable layer</b>, right beside the {@code uploads} volume that does survive. With no
 * volume behind it every recreate of {@code backend} destroys the files, and {@code backend} is
 * in {@code FACTORY_DEPLOY_RECREATABLE}, so that is every deploy.
 *
 * <p>Nothing errors when it happens, which is why this needs a test rather than a comment. The
 * Mongo documents survive, their Elasticsearch chunks survive, and those chunks carry the
 * {@code attachmentUrl} — so the assistant goes on citing PDFs whose bytes are gone and every
 * one of those links returns 404.
 *
 * <p>The mount must also stay <b>outside</b> {@code /workspace/uploads}: that path is served by a
 * {@code ResourceHandlerRegistry} mapping with no authorisation at all, so an attachment stored
 * there would be readable by anyone who knew a document id, restricted ones included — which is
 * the whole reason {@link com.simonrowe.school.SchoolAttachmentController} exists.
 */
class SchoolAttachmentPersistenceTest {

  private static final Path COMPOSE = Path.of("..", "docker-compose.prod.yml");
  private static final String MOUNT_POINT = "/workspace/school-attachments";

  @Test
  @DisplayName("production mounts a named volume for school attachments")
  void attachmentsAreStoredOnNamedVolume() throws IOException {
    final List<String> backend = serviceBlock("backend");

    assertThat(backend)
        .as("without a volume the bytes live in the container's writable layer and every "
            + "deploy deletes them, leaving citations that 404 with no error anywhere")
        .anySatisfy(line -> assertThat(line.trim())
            .isEqualTo("- school-attachments:" + MOUNT_POINT));

    assertThat(Files.readString(COMPOSE))
        .as("the named volume must also be declared")
        .containsPattern("(?m)^  school-attachments:\\s*$");
  }

  @Test
  @DisplayName("the configured path is absolute and matches the mount point")
  void pathMatchesTheMountPoint() throws IOException {
    assertThat(serviceBlock("backend"))
        .as("a relative path is resolved against the working directory, so it would miss the "
            + "mount and land in the writable layer again")
        .anySatisfy(line -> assertThat(line.trim())
            .isEqualTo("SCHOOL_ATTACHMENT_PATH: " + MOUNT_POINT + "/"));
  }

  @Test
  @DisplayName("attachments are not stored under the unauthenticated uploads path")
  void attachmentsAreNotUnderUploads() {
    assertThat(MOUNT_POINT)
        .as("uploads/ is served straight out with no tier check; a restricted attachment "
            + "there would be public to anyone who guessed a document id")
        .doesNotStartWith("/workspace/uploads");
  }

  /**
   * The lines of one service's block, from its two-space-indented key to the next one.
   *
   * @param service the compose service name
   * @return every line inside that service's block
   * @throws IOException when the compose file cannot be read
   */
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
