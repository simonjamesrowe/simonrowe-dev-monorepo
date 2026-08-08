package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DigestMetadataGeneratorTest {

  private static final String MODEL = "gpt-5.6-luna";

  @Mock private Ai ai;

  private PromptRunner promptRunner;
  private AssistantMessage assistantMessage;
  private DigestMetadataGenerator generator;

  private static AggregatedArticle article(final String title) {
    return new AggregatedArticle(
        "art-1", title, "InfoQ", "https://infoq.com",
        "https://infoq.com/art-1", "Summary.", "Content.", "Jane Doe",
        Instant.now(), Instant.now(), true, null);
  }

  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    assistantMessage = mock(AssistantMessage.class);
    lenient().when(ai.withLlm(MODEL)).thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList()))
        .thenReturn(assistantMessage);
    generator = new DigestMetadataGenerator(ai, MODEL);
  }

  @Test
  void parsesUsableResponseIntoTitleAndDescription() {
    when(assistantMessage.getContent()).thenReturn(
        "Title: What Spring Boot 4 means for us\n"
            + "Description: A look at the release and what it changes.");

    DigestMetadata metadata = generator.generate(
        List.of(article("Spring Boot 4")), "activity");

    assertThat(metadata.title()).isEqualTo("What Spring Boot 4 means for us");
    assertThat(metadata.shortDescription())
        .isEqualTo("A look at the release and what it changes.");
  }

  @Test
  void fallsBackWhenTitleStartsWithAiTechRoundup() {
    when(assistantMessage.getContent()).thenReturn(
        "Title: AI & Tech Roundup: Spring Boot 4\n"
            + "Description: A generic roundup.");

    DigestMetadata metadata = generator.generate(
        List.of(article("Spring Boot 4")), "activity");

    assertThat(metadata.title())
        .isEqualTo("What caught my eye: Spring Boot 4");
  }

  @Test
  void fallsBackWhenTitleContainsThisWeekInAi() {
    when(assistantMessage.getContent()).thenReturn(
        "Title: This Week In AI: Spring Boot 4\n"
            + "Description: A generic roundup.");

    DigestMetadata metadata = generator.generate(
        List.of(article("Spring Boot 4")), "activity");

    assertThat(metadata.title())
        .isEqualTo("What caught my eye: Spring Boot 4");
  }

  @Test
  void fallsBackWhenTheLlmCallFails() {
    when(promptRunner.respond(anyList()))
        .thenThrow(new RuntimeException("LLM timeout"));

    DigestMetadata metadata = generator.generate(
        List.of(article("Spring Boot 4")), "activity");

    assertThat(metadata.title())
        .isEqualTo("What caught my eye: Spring Boot 4");
  }

  @Test
  void fallsBackToTheGenericLeadWhenThereAreNoArticles() {
    when(promptRunner.respond(anyList()))
        .thenThrow(new RuntimeException("LLM timeout"));

    DigestMetadata metadata = generator.generate(List.of(), "activity");

    assertThat(metadata.title())
        .isEqualTo("What caught my eye: AI and backend engineering");
  }

  @Test
  void parsesBoldDecoratedLabelsFromNewerModel() {
    when(assistantMessage.getContent()).thenReturn(
        "**Title:** What Spring Boot 4 means for us\n"
            + "**Description:** A look at the release and what it changes.");

    DigestMetadata metadata = generator.generate(
        List.of(article("Spring Boot 4")), "activity");

    assertThat(metadata.title()).isEqualTo("What Spring Boot 4 means for us");
    assertThat(metadata.shortDescription())
        .isEqualTo("A look at the release and what it changes.");
  }

  @Test
  void parsesLabelsWrappedInCodeFence() {
    when(assistantMessage.getContent()).thenReturn(
        "```\n"
            + "Title: What Spring Boot 4 means for us\n"
            + "Description: A look at the release and what it changes.\n"
            + "```");

    DigestMetadata metadata = generator.generate(
        List.of(article("Spring Boot 4")), "activity");

    assertThat(metadata.title()).isEqualTo("What Spring Boot 4 means for us");
    assertThat(metadata.shortDescription())
        .isEqualTo("A look at the release and what it changes.");
  }

  @Test
  void theThreeArgOverloadPassesTheGivenModelNameThroughToAi() {
    PromptRunner pinnedRunner = mock(PromptRunner.class);
    AssistantMessage pinnedMessage = mock(AssistantMessage.class);
    when(ai.withLlm("gpt-4o-mini")).thenReturn(pinnedRunner);
    when(pinnedRunner.respond(anyList())).thenReturn(pinnedMessage);
    when(pinnedMessage.getContent()).thenReturn(
        "Title: Pinned title\nDescription: Pinned description.");

    DigestMetadata metadata = generator.generate(
        List.of(), "activity", "gpt-4o-mini");

    assertThat(metadata.title()).isEqualTo("Pinned title");
    verify(ai).withLlm("gpt-4o-mini");
  }
}
