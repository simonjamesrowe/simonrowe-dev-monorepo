package com.simonrowe.school.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.school.judge.PublicationJudge.Verdict;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** The judge's own failure handling: anything it cannot read leaves the email for a person. */
class PublicationJudgeTest {

  private final ChatModel chatModel = mock(ChatModel.class);
  private final PublicationJudge judge = new PublicationJudge(chatModel,
      new SchoolJudgeProperties(SchoolJudgeProperties.Mode.AUTO, null, 0, 0, List.of(), null,
          List.of(), List.of()),
      mock(SchoolUsageRecorder.class));

  private void modelSays(final String text) {
    when(chatModel.call(any(Prompt.class))).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
  }

  @Test
  void readsStructuredVerdict() {
    modelSays("{\"verdict\":\"PUBLIC\",\"confidence\":0.98,\"reason\":\"A newsletter.\"}");

    final PublicationJudge.Answer answer = judge.judge("info@school.example", "News", "Hi");

    assertThat(answer.verdict()).isEqualTo(Verdict.PUBLIC);
    assertThat(answer.confidence()).isEqualTo(0.98);
    assertThat(answer.reason()).isEqualTo("A newsletter.");
  }

  @Test
  void failedCallIsUnsure() {
    when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("timeout"));

    assertThat(judge.judge("a", "b", "c").verdict()).isEqualTo(Verdict.UNSURE);
  }

  @Test
  void anUnreadableAnswerIsUnsure() {
    modelSays("PUBLIC");

    assertThat(judge.judge("a", "b", "c").verdict()).isEqualTo(Verdict.UNSURE);
  }

  @Test
  void anUnknownVerdictIsUnsure() {
    modelSays("{\"verdict\":\"SHAREABLE\",\"confidence\":1,\"reason\":\"x\"}");

    assertThat(judge.judge("a", "b", "c").verdict()).isEqualTo(Verdict.UNSURE);
  }

  @Test
  void confidenceIsClampedSoItCannotClearThresholdByBeingOutOfRange() {
    modelSays("{\"verdict\":\"PUBLIC\",\"confidence\":7,\"reason\":\"x\"}");
    assertThat(judge.judge("a", "b", "c").confidence()).isEqualTo(1.0);

    modelSays("{\"verdict\":\"PUBLIC\",\"confidence\":-2,\"reason\":\"x\"}");
    assertThat(judge.judge("a", "b", "c").confidence()).isZero();
  }
}
