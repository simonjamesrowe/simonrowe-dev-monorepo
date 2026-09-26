package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the origin that PDF citations in an answer are built against.
 *
 * <p>Attachment links used to be the relative {@code /api/school/attachments/<id>}. A model
 * writing a markdown link has to supply an origin, and it invented the school's own domain —
 * because that is what the rest of the answer cites — so every PDF link in a live answer
 * resolved to {@code https://www.kilmorieschool.co.uk/api/school/attachments/...} and 404'd.
 */
class SchoolAttachmentUrlTest {

  private static SchoolProperties withBaseUrl(final String baseUrl) {
    return new SchoolProperties(
        true, null, List.of(), List.of(), null, null, null, 0, null, null, null, 0L,
        baseUrl, null);
  }

  @Test
  @DisplayName("the configured base URL is absolute, so a model never has to invent an origin")
  void baseUrlIsAbsolute() {
    assertThat(withBaseUrl(null).publicBaseUrl()).startsWith("https://");
    assertThat(withBaseUrl("").publicBaseUrl()).startsWith("https://");
  }

  @Test
  @DisplayName("a trailing slash is removed so a rooted path does not double it")
  void trailingSlashIsRemoved() {
    assertThat(withBaseUrl("https://term-time.simonrowe.dev/").publicBaseUrl())
        .isEqualTo("https://term-time.simonrowe.dev");
  }

  @Test
  @DisplayName("an explicit origin is honoured, so local development can point at itself")
  void explicitOriginIsHonoured() {
    assertThat(withBaseUrl("http://localhost:5173").publicBaseUrl())
        .isEqualTo("http://localhost:5173");
  }

  @Test
  @DisplayName("compose sets the origin explicitly rather than leaving it to interpolate empty")
  void composeDeclaresTheOrigin() throws Exception {
    // `${VAR:-}` passes an empty STRING, which Spring resolves successfully — so the
    // application.yml default would never apply and every link would go relative again. The
    // same trap the ingest cutoff documents, one variable over.
    final String compose = Files.readString(Path.of("..", "docker-compose.prod.yml"));

    assertThat(compose).contains("SCHOOL_PUBLIC_BASE_URL:");
    assertThat(compose).doesNotContain("SCHOOL_PUBLIC_BASE_URL: ${SCHOOL_PUBLIC_BASE_URL:-}");
    assertThat(compose).contains("SCHOOL_PUBLIC_BASE_URL: ${SCHOOL_PUBLIC_BASE_URL:-https://");
  }
}
