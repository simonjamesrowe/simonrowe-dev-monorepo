package com.simonrowe.coparent.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.DueKind;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The reader only proposes, and only values that survive checking. */
class DocumentReaderTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
  private static final List<DocumentReader.Child> CHILDREN = List.of(
      new DocumentReader.Child("aaaaaaaaaaaaaaaaaaaaaaaa", "Mia"),
      new DocumentReader.Child("bbbbbbbbbbbbbbbbbbbbbbbb", "Leo"));

  private final ChatModel chatModel = mock(ChatModel.class);

  @Test
  void keepsOnlyTheFamilysChildrenAndValidValues() {
    answer("""
        {"childIds":["bbbbbbbbbbbbbbbbbbbbbbbb","cccccccccccccccccccccccc",
          "bbbbbbbbbbbbbbbbbbbbbbbb"],
         "section":"IDENTITY","title":"Passport","datedOn":"2025-08-02",
         "dueOn":"2035-08-02","dueKind":"EXPIRES","confidence":"high"}""");

    final DocumentReader.Proposal proposal = reader(true).read(TODAY, CHILDREN,
        List.of(new DocumentUploads.Upload("image/jpeg", DocumentTestFiles.jpeg())));

    assertThat(proposal.childIds()).containsExactly("bbbbbbbbbbbbbbbbbbbbbbbb");
    assertThat(proposal.section()).isEqualTo(DocumentSection.IDENTITY);
    assertThat(proposal.title()).isEqualTo("Passport");
    assertThat(proposal.datedOn()).isEqualTo(LocalDate.of(2025, 8, 2));
    assertThat(proposal.dueOn()).isEqualTo(LocalDate.of(2035, 8, 2));
    assertThat(proposal.dueKind()).isEqualTo(DueKind.EXPIRES);
    assertThat(proposal.confidence()).isEqualTo("high");
  }

  @Test
  void dropsDueDateWithoutItsKindAndAnythingUnparseable() {
    answer("""
        {"childIds":[],"section":"PASSPORTS","title":"%s","datedOn":"02/08/2025",
         "dueOn":"2027-01-10","dueKind":null,"confidence":"certain"}""".formatted("x".repeat(200)));

    final DocumentReader.Proposal proposal = reader(true).read(TODAY, CHILDREN,
        List.of(new DocumentUploads.Upload("image/png", DocumentTestFiles.png())));

    assertThat(proposal.section()).isNull();
    assertThat(proposal.title()).hasSize(120);
    assertThat(proposal.datedOn()).isNull();
    assertThat(proposal.dueOn()).isNull();
    assertThat(proposal.dueKind()).isNull();
    assertThat(proposal.confidence()).isNull();
  }

  @Test
  void sendsImagesAsTheyAreWithStructuredOutputAndNoTools() {
    answer("{\"childIds\":[]}");

    reader(true).read(TODAY, CHILDREN, List.of(
        new DocumentUploads.Upload("image/webp", DocumentTestFiles.webp())));

    final ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    final OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertThat(options.getResponseFormat().getType())
        .isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
    // No tools: null or empty, depending on the Spring AI version.
    assertThat(options.getToolCallbacks()).isNullOrEmpty();
    final UserMessage user = (UserMessage) prompt.getValue().getInstructions().getLast();
    assertThat(user.getMedia()).singleElement()
        .satisfies(media -> assertThat(media.getMimeType().toString()).isEqualTo("image/webp"));
    assertThat(prompt.getValue().getInstructions().getFirst().getText())
        .contains("Never copy out a passport number", "\"firstName\":\"Mia\"");
  }

  @Test
  void pdfGoesAsItsFirstFivePagesRendered() {
    final List<Media> media = DocumentReader.media(List.of(
        new DocumentUploads.Upload("application/pdf", DocumentTestFiles.pdf(7))));

    assertThat(media).hasSize(5)
        .allSatisfy(page -> assertThat(page.getMimeType().toString()).isEqualTo("image/jpeg"));
  }

  @Test
  void unreadablePdfIsClientError() {
    assertThatThrownBy(() -> DocumentReader.media(List.of(
        new DocumentUploads.Upload("application/pdf", DocumentTestFiles.fakePdf()))))
        .isInstanceOfSatisfying(ResponseStatusException.class, refused ->
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void switchedOffIsUnavailableAndCallsNothing() {
    assertThatThrownBy(() -> reader(false).read(TODAY, CHILDREN,
        List.of(new DocumentUploads.Upload("image/jpeg", DocumentTestFiles.jpeg()))))
        .isInstanceOfSatisfying(ResponseStatusException.class, refused ->
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    verify(chatModel, never()).call(any(Prompt.class));
  }

  @Test
  void malformedOrEmptyAnswerIsBadGateway() {
    answer("not json");
    assertThatThrownBy(() -> reader(true).read(TODAY, CHILDREN,
        List.of(new DocumentUploads.Upload("image/jpeg", DocumentTestFiles.jpeg()))))
        .isInstanceOfSatisfying(ResponseStatusException.class, refused ->
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    assertThatThrownBy(() -> reader(true).proposal(null, CHILDREN))
        .isInstanceOf(ResponseStatusException.class);
  }

  private void answer(final String json) {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
  }

  private DocumentReader reader(final boolean enabled) {
    return new DocumentReader(chatModel, new CoparentProperties(true, "coparent",
        "https://coparents.example", "email", "noreply@example.com", false, "legacy",
        new CoparentProperties.Assistant(enabled, "gpt-test")));
  }
}
