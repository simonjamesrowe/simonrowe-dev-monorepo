package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two instructions that only exist because the assistant was answering too thinly.
 *
 * <p>Prompt text is awkward to test and mostly should not be, but these two are not style. Each
 * replaced a rule that was actively producing a bad answer, and each is one careless edit away
 * from coming back — "be brief and practical, do not pad" reads like obviously good guidance
 * right up until you watch it summarise a week of school letters into three bullets.
 *
 * <p>Asserting the instruction is present is all that can be done here; whether the model obeys
 * it is an evals question, not a unit-test one. That is a real limit and the reason these are
 * kept to the two rules with a known failure behind them rather than a checklist of the prompt.
 */
class SchoolAnswerDepthTest {

  @Test
  @DisplayName("the prompt no longer tells the assistant to be brief unconditionally")
  void lengthScalesToTheQuestion() {
    // The old STYLE section opened "Be brief and practical. Parents are usually checking one
    // fact." True of "when is half term" and wrong about everything else: asked what the latest
    // news was, it returned three bullets from a week of letters and the reader had to ask
    // twice more to get the rest of what it had already read.
    assertThat(SchoolSystemPrompt.TEXT)
        .doesNotContain("Be brief and practical")
        .contains("match the LENGTH to what was asked")
        .contains("gets EVERYTHING you found")
        .contains("When in doubt, give more");
  }

  @Test
  @DisplayName("a question about the assistant's own coverage is answerable, not deflected")
  void coverageQuestionsAreAnswered() {
    assertThat(SchoolSystemPrompt.TEXT)
        .contains("asked why you do not have something")
        .contains("Do not deflect that question");
  }

  @Test
  @DisplayName("the topic filter admits follow-ups and questions about the assistant")
  void guardrailAdmitsFollowUpsAndMetaQuestions() throws Exception {
    // The classifier is shown ONE message with no conversation around it, so "Why don't we have
    // the contents available?" scores as off-topic and gets the flat refusal — at the exact
    // moment the reader has spotted a gap and is deciding whether to trust anything it said.
    final Field field = SchoolTopicGuardrail.class.getDeclaredField("CLASSIFIER_PROMPT");
    field.setAccessible(true);
    final String prompt = (String) field.get(null);

    assertThat(prompt)
        .contains("FOLLOW-UP")
        .contains("ABOUT THIS ASSISTANT");
  }
}
