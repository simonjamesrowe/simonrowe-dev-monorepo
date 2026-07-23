package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContextAwareQuestionAnswerAdvisorTest {

  @Test
  void promptTemplateFramesContextAsSupplementaryAndEncouragesTools() {
    String template = ContextAwareQuestionAnswerAdvisor.PROMPT_TEMPLATE;

    // The retrieved context is background/reference, not the sole source.
    assertThat(template).contains("reference material");
    assertThat(template).contains("not as the only source");

    // The old suppression that stopped the model calling tools must be gone.
    assertThat(template).doesNotContain("not prior knowledge");
    assertThat(template).doesNotContain("you can't answer the question");

    // Content questions should still trigger the matching tool so the card renders.
    assertThat(template).contains("call the matching");
    assertThat(template).contains("visual card");

    // The user's question and the retrieval placeholder are still rendered.
    assertThat(template).contains("{query}");
    assertThat(template).contains("{question_answer_context}");
  }
}
