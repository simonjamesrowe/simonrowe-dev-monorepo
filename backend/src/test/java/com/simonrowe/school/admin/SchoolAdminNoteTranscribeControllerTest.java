package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.classify.SchoolNoteTranscriber;
import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.usage.SchoolUsageRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class SchoolAdminNoteTranscribeControllerTest {

  private final SchoolNoteTranscriber transcriber = mock(SchoolNoteTranscriber.class);
  private final SchoolAdminController controller = new SchoolAdminController(
      mock(MongoTemplate.class),
      mock(SchoolSyncStateRepository.class),
      mock(SchoolApprovalService.class),
      mock(SchoolIngestTrigger.class),
      mock(SchoolUsageRepository.class),
      mock(SchoolAttachmentStore.class),
      mock(SchoolLinkRepository.class),
      mock(SchoolLinkFetcher.class),
      mock(SchoolNoteService.class),
      transcriber);

  @Test
  @DisplayName("SVG and PDF uploads are rejected before untrusted bytes reach the model")
  void nonPhotographsAreRejected() {
    assertStatus("image/svg+xml", 400);
    assertStatus("application/pdf", 400);

    verify(transcriber, never()).transcribe(any(), any());
  }

  @Test
  @DisplayName("an image over 10 MB is a 413 rather than an expensive model call")
  void oversizedImagesAreRejected() {
    final byte[] bytes = new byte[10 * 1024 * 1024 + 1];
    final MockMultipartFile file = new MockMultipartFile(
        "file", "large.jpg", "image/jpeg", bytes);

    final ResponseStatusException exception = catchThrowableOfType(
        () -> controller.transcribeNoteImage(file), ResponseStatusException.class);

    assertThat(exception.getStatusCode().value()).isEqualTo(413);
    verify(transcriber, never()).transcribe(any(), any());
  }

  @Test
  @DisplayName("an empty transcription is a 422, never a successful empty editor")
  void emptyTranscriptionIsRejected() {
    final MockMultipartFile file = image();
    when(transcriber.transcribe(any(), eq("image/jpeg"))).thenReturn(Optional.empty());

    final ResponseStatusException exception = catchThrowableOfType(
        () -> controller.transcribeNoteImage(file), ResponseStatusException.class);

    assertThat(exception.getStatusCode().value()).isEqualTo(422);
    assertThat(exception.getReason()).isEqualTo("Nothing readable came back from that photo.");
  }

  @Test
  @DisplayName("a readable photograph returns both editable text and a useful filing title")
  void readablePhotographIsReturned() {
    when(transcriber.transcribe(any(), eq("image/jpeg"))).thenReturn(Optional.of(
        new SchoolNoteTranscriber.Transcription("Autumn Week 2\nplayed", "Spellings - week 2")));

    final ResponseEntity<SchoolAdminController.TranscriptionResponse> response =
        controller.transcribeNoteImage(image());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().text()).contains("Autumn Week 2", "played");
    assertThat(response.getBody().title()).isEqualTo("Spellings - week 2");
  }

  private void assertStatus(final String mimeType, final int expected) {
    final MockMultipartFile file = new MockMultipartFile("file", "page", mimeType,
        new byte[] {1});
    final ResponseStatusException exception = catchThrowableOfType(
        () -> controller.transcribeNoteImage(file), ResponseStatusException.class);
    assertThat(exception.getStatusCode().value()).isEqualTo(expected);
  }

  private static MockMultipartFile image() {
    return new MockMultipartFile("file", "page.jpg", "image/jpeg", new byte[] {1, 2, 3});
  }
}
