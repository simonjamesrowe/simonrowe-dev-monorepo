package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DigestComposerTest {

  private static final String MODEL = "gpt-5.6-luna";

  private static final DigestSection SECTION_ONE = new DigestSection(
      "art-1", "Spring Boot 4 Released", "https://infoq.com/spring-boot-4",
      "Body about Spring Boot.", false);
  private static final DigestSection SECTION_TWO = new DigestSection(
      "art-2", "Postgres 19 Ships", "https://pg.org/pg19",
      "Body about Postgres.", false);

  @Mock private Ai ai;

  private PromptRunner promptRunner;
  private AssistantMessage assistantMessage;
  private DigestComposer composer;

  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    assistantMessage = mock(AssistantMessage.class);
    lenient().when(ai.withLlm(MODEL)).thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList())).thenReturn(assistantMessage);
    composer = new DigestComposer(ai, MODEL);
  }

  @Test
  void usesSynthesisWhenEveryUrlSurvives() {
    when(assistantMessage.getContent()).thenReturn("""
        A flowing intro.

        ## [Spring Boot 4 Released](https://infoq.com/spring-boot-4)
        Rewritten prose about Spring Boot.

        ## [Postgres 19 Ships](https://pg.org/pg19)
        Rewritten prose about Postgres.
        """);

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("Rewritten prose about Spring Boot.");
    assertThat(result).contains("https://infoq.com/spring-boot-4");
    assertThat(result).contains("https://pg.org/pg19");
  }

  @Test
  void fallsBackToAssembledDocumentWhenSynthesisDropsUrl() {
    when(assistantMessage.getContent()).thenReturn("""
        A flowing intro.

        ## Spring Boot 4 Released
        Rewritten prose, but the link is gone.

        ## [Postgres 19 Ships](https://pg.org/pg19)
        Rewritten prose about Postgres.
        """);

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("[Spring Boot 4 Released](https://infoq.com/spring-boot-4)");
    assertThat(result).contains("Body about Spring Boot.");
    assertThat(result).doesNotContain("Rewritten prose");
  }

  @Test
  void fallsBackToAssembledDocumentWhenSynthesisThrows() {
    when(promptRunner.respond(anyList()))
        .thenThrow(new RuntimeException("upstream 500"));

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("[Spring Boot 4 Released](https://infoq.com/spring-boot-4)");
    assertThat(result).contains("[Postgres 19 Ships](https://pg.org/pg19)");
    assertThat(result).contains("Body about Postgres.");
  }

  @Test
  void fallsBackToAssembledDocumentWhenSynthesisIsBlank() {
    when(assistantMessage.getContent()).thenReturn("   ");

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("[Postgres 19 Ships](https://pg.org/pg19)");
  }

  @Test
  void fallsBackToAssembledDocumentWhenSynthesisAddedTopLevelHeading() {
    when(assistantMessage.getContent()).thenReturn("""
        # This Week's Digest
        A flowing intro.

        ## [Spring Boot 4 Released](https://infoq.com/spring-boot-4)
        Rewritten prose about Spring Boot.

        ## [Postgres 19 Ships](https://pg.org/pg19)
        Rewritten prose about Postgres.
        """);

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("[Spring Boot 4 Released](https://infoq.com/spring-boot-4)");
    assertThat(result).contains("Body about Spring Boot.");
    assertThat(result).doesNotContain("# This Week's Digest");
    assertThat(result).doesNotContain("Rewritten prose");
  }

  @Test
  void usesSynthesisWithOnlyDoubleHashHeadings() {
    when(assistantMessage.getContent()).thenReturn("""
        A flowing intro.

        ## [Spring Boot 4 Released](https://infoq.com/spring-boot-4)
        Rewritten prose about Spring Boot.

        ## [Postgres 19 Ships](https://pg.org/pg19)
        Rewritten prose about Postgres.
        """);

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("A flowing intro.");
    assertThat(result).contains("Rewritten prose about Spring Boot.");
    assertThat(result).contains("## [Spring Boot 4 Released](https://infoq.com/spring-boot-4)");
    assertThat(result).contains("## [Postgres 19 Ships](https://pg.org/pg19)");
  }
}
