package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class SchoolEventMergerTest {

  private final ChatModel chatModel = mock(ChatModel.class);
  private final List<SchoolEventMerger.Candidate> candidates = List.of(
      new SchoolEventMerger.Candidate("e1",
          List.of(SchoolEventMatcherTest.event("Harriet's class trip", List.of()))),
      new SchoolEventMerger.Candidate("e2", List.of(SchoolEventMatcherTest.event(
          "Year 3 Local Area Walk – Harriet's class", List.of("Year 3")))));

  @Test
  void keepsOnlyKeysItWasGivenAndEachKeyOnce() {
    answer("""
        {"events":[
          {"members":["e1","e2","e9"],"title":"Local Area Walk","startTime":"09:15",
           "endTime":"10:45"},
          {"members":["e2","x1"],"title":"Again","startTime":null,"endTime":null}]}""");

    final List<SchoolEventMerger.Entry> plan = merger(true).plan(candidates, List.of(
        new SchoolEventMerger.Existing("x1", "Winter Fair", LocalDate.of(2026, 11, 27), null)));

    assertThat(plan).hasSize(2);
    assertThat(plan.get(0).members()).containsExactly("e1", "e2");
    assertThat(plan.get(0).title()).isEqualTo("Local Area Walk");
    assertThat(plan.get(1).members()).containsExactly("x1");
  }

  @Test
  void asksWithNoToolsAndStrictSchema() {
    answer("{\"events\":[]}");
    merger(true).plan(candidates, List.of());

    final ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    final OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertThat(options.getModel()).isEqualTo("test-model");
    assertThat(options.getToolCallbacks()).isNullOrEmpty();
    assertThat(prompt.getValue().getContents()).contains("Harriet's class trip");
  }

  @Test
  void anyFailureMeansNoPlanRatherThanNoSuggestions() {
    when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("down"));
    assertThat(merger(true).plan(candidates, List.of())).isEmpty();

    answer("not json");
    assertThat(merger(true).plan(candidates, List.of())).isEmpty();
  }

  @Test
  void switchedOffItAsksNothing() {
    assertThat(merger(false).plan(candidates, List.of())).isEmpty();
    verify(chatModel, never()).call(any(Prompt.class));
  }

  private void answer(final String json) {
    when(chatModel.call(any(Prompt.class))).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
  }

  private SchoolEventMerger merger(final boolean ai) {
    return new SchoolEventMerger(chatModel,
        new SchoolCalendarProperties(true, ai, "test-model", 60, ""));
  }
}
