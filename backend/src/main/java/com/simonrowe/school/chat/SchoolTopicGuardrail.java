package com.simonrowe.school.chat;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Decides whether a question is about the school before a full turn is spent on it.
 *
 * <p>Runs on the cheapest capable model. It fires on every turn, so a guardrail costing more than
 * the answer it guards is a real possibility — that is exactly the state the portfolio chat's
 * guardrail is in, hardcoded to an older and dearer model than the chat itself.
 *
 * <p><b>Fails open.</b> If the classifier errors, the question is allowed through. A guardrail
 * outage must degrade to "answers off-topic questions occasionally", not "the assistant is down" —
 * and the system prompt already refuses to answer things it has no sources for, so an off-topic
 * question that slips past gets a don't-know rather than an invention.
 */
@Component
public class SchoolTopicGuardrail {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolTopicGuardrail.class);

  private static final String CLASSIFIER_PROMPT = """
      You are a topic filter for an assistant about a UK primary school.

      Answer YES if the question could plausibly relate to a primary school: term dates, \
      holidays, INSET days, events, clubs, uniform, school lunches, PE, trips, staff, \
      newsletters, term times, school policies, or day-to-day school arrangements.

      Answer NO for anything else.

      Answer with exactly one word: YES or NO.

      Question: %s
      """;

  private final ChatModel chatModel;
  private final SchoolProperties properties;
  private final SchoolUsageRecorder usageRecorder;

  public SchoolTopicGuardrail(final ChatModel chatModel, final SchoolProperties properties,
      final SchoolUsageRecorder usageRecorder) {
    this.chatModel = chatModel;
    this.properties = properties;
    this.usageRecorder = usageRecorder;
  }

  /**
   * Whether a question is on topic.
   *
   * @param question the visitor's question
   * @return true when the question should be answered
   */
  public boolean isAboutSchool(final String question) {
    if (question == null || question.isBlank()) {
      return false;
    }
    try {
      final String verdict = chatModel.call(new Prompt(
              CLASSIFIER_PROMPT.formatted(question),
              OpenAiChatOptions.builder().model(properties.guardrailModel()).build()))
          .getResult().getOutput().getText();
      usageRecorder.recordEstimated(SchoolUsage.Kind.GUARDRAIL, properties.guardrailModel(),
          CLASSIFIER_PROMPT.length() + question.length(), verdict == null ? 0 : verdict.length());
      return verdict == null || !verdict.trim().toUpperCase(java.util.Locale.ROOT).startsWith("NO");
    } catch (RuntimeException e) {
      LOG.warn("School topic guardrail failed, allowing through: {}", e.getMessage());
      return true;
    }
  }
}
