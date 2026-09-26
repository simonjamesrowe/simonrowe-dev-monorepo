package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * The only route by which an ingested PDF reaches a reader.
 *
 * <p>This endpoint is unauthenticated, like the rest of Term Time, so the tier check here is the
 * entire access control over school attachments. It answers <b>404, never 403</b>, for a
 * restricted document: a 403 confirms the file exists, which for a school mailbox is itself the
 * disclosure worth avoiding.
 */
class SchoolAttachmentControllerTest {

  private static final byte[] PDF = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);

  private SchoolDocumentRepository documents;
  private SchoolAttachmentStore store;

  @BeforeEach
  void setUp() {
    documents = mock(SchoolDocumentRepository.class);
    store = mock(SchoolAttachmentStore.class);
  }

  private SchoolAttachmentController controllerWith(final boolean enabled) {
    return new SchoolAttachmentController(documents, store, new SchoolProperties(
        enabled, null, List.of(), List.of(), null, null, null, 0, null, null, null, 0L,
        null, null));
  }

  private static SchoolDocument document(final Visibility visibility, final String title) {
    return new SchoolDocument("doc-1", SchoolSourceType.PDF, "gmail:1:2", title, "body",
        Instant.EPOCH, Instant.EPOCH, visibility, null, null, null, null, false,
        List.of(), "hash", null);
  }

  @Test
  @DisplayName("a public attachment is served inline as a PDF with a readable filename")
  void servesPublicAttachment() {
    when(documents.findById("doc-1"))
        .thenReturn(Optional.of(document(Visibility.PUBLIC, "Autumn lunch menu")));
    when(store.read("doc-1")).thenReturn(Optional.of(PDF));

    final var response = controllerWith(true).attachment("doc-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(PDF);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .startsWith("inline;")
        .contains("Autumn lunch menu.pdf");
  }

  @Test
  @DisplayName("a restricted attachment is 404, never 403 — a 403 confirms it exists")
  void restrictedAttachmentIsNotFound() {
    when(documents.findById("doc-1"))
        .thenReturn(Optional.of(document(Visibility.RESTRICTED, "Private letter")));

    final var response = controllerWith(true).attachment("doc-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNull();
    org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).read(any());
  }

  @Test
  @DisplayName("an unknown document is 404")
  void unknownDocumentIsNotFound() {
    when(documents.findById("nope")).thenReturn(Optional.empty());

    assertThat(controllerWith(true).attachment("nope").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("a public document whose file is missing is 404 rather than an empty 200")
  void missingFileIsNotFound() {
    when(documents.findById("doc-1"))
        .thenReturn(Optional.of(document(Visibility.PUBLIC, "Menu")));
    when(store.read("doc-1")).thenReturn(Optional.empty());

    assertThat(controllerWith(true).attachment("doc-1").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("the endpoint serves nothing at all while the feature is switched off")
  void disabledFeatureServesNothing() {
    // school.enabled gates every part of Term Time. The repository must not even be consulted,
    // or a half-configured deployment would still hand out files.
    assertThat(controllerWith(false).attachment("doc-1").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    org.mockito.Mockito.verifyNoInteractions(documents, store);
  }

  @Test
  @DisplayName("a title with path or quote characters cannot break out of the filename header")
  void filenameIsSanitised() {
    when(documents.findById("doc-1"))
        .thenReturn(Optional.of(document(Visibility.PUBLIC, "../../etc/\"passwd\"")));
    when(store.read("doc-1")).thenReturn(Optional.of(PDF));

    final String disposition = controllerWith(true).attachment("doc-1")
        .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);

    // The property that matters: no separators, and exactly the two quotes that delimit the
    // value, so a crafted title cannot inject a second header directive. Dots survive (the
    // title becomes "....etcpasswd.pdf") and that is cosmetic rather than unsafe - the file is
    // located by document id, never by this string, and it is only ever a suggested filename.
    assertThat(disposition).doesNotContain("/").doesNotContain("\\");
    assertThat(disposition.chars().filter(c -> c == '"').count()).isEqualTo(2);
  }

  @Test
  @DisplayName("a blank title still yields a usable filename")
  void blankTitleFallsBack() {
    when(documents.findById("doc-1")).thenReturn(Optional.of(document(Visibility.PUBLIC, "  ")));
    when(store.read("doc-1")).thenReturn(Optional.of(PDF));

    assertThat(controllerWith(true).attachment("doc-1")
        .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .contains("school-document");
  }
}
