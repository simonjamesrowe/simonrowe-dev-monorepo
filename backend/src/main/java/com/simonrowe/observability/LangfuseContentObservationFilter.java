package com.simonrowe.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/**
 * Copies prompt and completion content onto the span attribute names Langfuse recognises.
 *
 * <p>Spring AI does not do this itself at any version: {@code log-prompt} / {@code
 * log-completion} only produce SLF4J log lines, and {@code AiObservationAttributes} has no
 * prompt or completion constant. Without this filter, Langfuse shows generations with null
 * input and output, which also makes LLM-as-a-judge evaluators useless.
 *
 * <p>Both chat-model and tool-call content are read from the observation context's own public
 * accessors, so this filter does not depend on its registration order relative to any other
 * filter. An earlier version instead copied the {@code spring.ai.tool.call.arguments} /
 * {@code .result} key values written by Spring AI's {@code ToolCallingContentObservationFilter},
 * which requires that filter to run first. It does not: both beans end up at
 * {@code Ordered.LOWEST_PRECEDENCE} ({@code Integer.MAX_VALUE}) — explicitly here, implicitly
 * there — and Boot's {@code ObservationRegistryConfigurer} sorts them with a stable sort that
 * falls back to bean-registration order, which places component-scanned configuration ahead of
 * deferred auto-configuration. The copy therefore always found nothing.
 */
public class LangfuseContentObservationFilter implements ObservationFilter {

  private static final Logger LOG =
      LoggerFactory.getLogger(LangfuseContentObservationFilter.class);

  private final LangfuseProperties properties;

  public LangfuseContentObservationFilter(final LangfuseProperties properties) {
    this.properties = properties;
  }

  @Override
  public Observation.Context map(final Observation.Context context) {
    if (!properties.isContentCaptureEnabled()) {
      return context;
    }
    try {
      if (context instanceof ChatModelObservationContext chatContext) {
        mapChatContent(chatContext);
      } else if (context instanceof ToolCallingObservationContext toolContext) {
        mapToolContent(toolContext);
      }
    } catch (Exception e) {
      // Never let observability break the call it is observing.
      LOG.warn("Failed to attach Langfuse content attributes", e);
    }
    return context;
  }

  private void mapChatContent(final ChatModelObservationContext context) {
    if (context.getRequest() != null && context.getRequest().getInstructions() != null) {
      String prompt = context.getRequest().getInstructions().stream()
          .map(LangfuseContentObservationFilter::renderMessage)
          .collect(Collectors.joining("\n"));
      put(context, LangfuseAttributes.OBSERVATION_INPUT, prompt);
    }

    ChatResponse response = context.getResponse();
    if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
      String completion = response.getResults().stream()
          .map(LangfuseContentObservationFilter::renderGeneration)
          .collect(Collectors.joining("\n"));
      put(context, LangfuseAttributes.OBSERVATION_OUTPUT, completion);
    }
  }

  /**
   * Reads the tool arguments and result straight off the context. {@code getToolCallResult()} is
   * null until the tool has returned, so an in-flight or failed tool call contributes input only.
   */
  private void mapToolContent(final ToolCallingObservationContext context) {
    put(context, LangfuseAttributes.OBSERVATION_INPUT, context.getToolCallArguments());
    put(context, LangfuseAttributes.OBSERVATION_OUTPUT, context.getToolCallResult());
  }

  private void put(final Observation.Context context, final String key, final String value) {
    String capped = LangfuseAttributes.truncate(value);
    if (capped != null && !capped.isEmpty()) {
      context.addHighCardinalityKeyValue(KeyValue.of(key, capped));
    }
  }

  private static String renderMessage(final Message message) {
    String text = message.getText();
    return message.getMessageType() + ": " + (text == null ? "" : text);
  }

  private static String renderGeneration(final Generation generation) {
    if (generation.getOutput() == null || generation.getOutput().getText() == null) {
      return "";
    }
    return generation.getOutput().getText();
  }
}
