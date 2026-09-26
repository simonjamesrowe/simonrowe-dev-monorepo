package com.simonrowe.school.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.AgentImage;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SchoolNoteTranscriberTest {

  private static final String VISION_MODEL = "vision-model";
  private static final String GUARDRAIL_MODEL = "text-only-model";

  private final Ai ai = mock(Ai.class);
  private final PromptRunner runner = mock(PromptRunner.class);
  private final PromptRunner imageRunner = mock(PromptRunner.class);
  private final SchoolUsageRecorder usage = mock(SchoolUsageRecorder.class);
  private final SchoolNoteTranscriber transcriber =
      new SchoolNoteTranscriber(ai, properties(), usage);

  @BeforeEach
  void setUp() {
    when(ai.withLlm(VISION_MODEL)).thenReturn(runner);
    when(runner.withImage(any(AgentImage.class))).thenReturn(imageRunner);
  }

  @Test
  @DisplayName("the original MIME type and bytes reach the vision model")
  void sendsTheImageAsReceived() {
    final byte[] bytes = {1, 2, 3};
    when(imageRunner.createObjectIfPossible(anyString(), eq(TranscribedNote.class)))
        .thenReturn(new TranscribedNote("Autumn Week 2", "Spellings - Autumn week 2"));
    final ArgumentCaptor<AgentImage> image = ArgumentCaptor.forClass(AgentImage.class);

    assertThat(transcriber.transcribe(bytes, "image/png")).isPresent();

    verify(runner).withImage(image.capture());
    assertThat(image.getValue().getMimeType()).isEqualTo("image/png");
    assertThat(image.getValue().getData()).containsExactly(bytes);
  }

  @Test
  @DisplayName("transcription uses the vision model, never the cheaper text guardrail model")
  void usesTheVisionModel() {
    when(imageRunner.createObjectIfPossible(anyString(), eq(TranscribedNote.class)))
        .thenReturn(new TranscribedNote("Readable text", "A title"));

    transcriber.transcribe(new byte[] {1}, "image/jpeg");

    verify(ai).withLlm(VISION_MODEL);
    verify(ai, never()).withLlm(GUARDRAIL_MODEL);
  }

  @Test
  @DisplayName("a null model result is an empty optional so the endpoint can return 422")
  void nullResultIsEmpty() {
    when(imageRunner.createObjectIfPossible(anyString(), eq(TranscribedNote.class)))
        .thenReturn(null);

    assertThat(transcriber.transcribe(new byte[] {1}, "image/jpeg")).isEmpty();
  }

  @Test
  @DisplayName("a model failure is an empty optional rather than an operator-facing 500")
  void modelFailureIsEmpty() {
    when(imageRunner.createObjectIfPossible(anyString(), eq(TranscribedNote.class)))
        .thenThrow(new RuntimeException("provider unavailable"));

    assertThat(transcriber.transcribe(new byte[] {1}, "image/jpeg")).isEmpty();
  }

  @Test
  @DisplayName("a title without readable page text is not accepted as a transcription")
  void titleAloneIsEmpty() {
    when(imageRunner.createObjectIfPossible(anyString(), eq(TranscribedNote.class)))
        .thenReturn(new TranscribedNote("   ", "Spellings"));

    assertThat(transcriber.transcribe(new byte[] {1}, "image/jpeg")).isEmpty();
  }

  @Test
  @DisplayName("image usage is visible as an estimated transcription charge")
  void recordsTranscriptionUsage() {
    when(imageRunner.createObjectIfPossible(anyString(), eq(TranscribedNote.class)))
        .thenReturn(new TranscribedNote("Readable text", "A title"));

    transcriber.transcribe(new byte[] {1}, "image/jpeg");

    verify(usage).recordEstimatedTokens(
        eq(SchoolUsage.Kind.TRANSCRIBE), eq(VISION_MODEL), anyLong(), anyLong());
  }

  private static SchoolProperties properties() {
    return new SchoolProperties(
        true, null, List.of(), List.of(), null, null, null, 0,
        "chat-model", GUARDRAIL_MODEL, VISION_MODEL, 0, null, null);
  }
}
