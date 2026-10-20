package com.simonrowe.meals;

import com.simonrowe.observability.LangfuseContentObservationFilter;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * One structured-output call to the meals model, the same shape as CoParent's statement
 * classifier: a JSON schema in {@code response_format} (so the model has nothing to call), no
 * reasoning effort, content kept out of Langfuse (it is the owner's kitchen and copyrighted
 * recipes), and a bound of its own because the SDK's timeout is ten minutes: 45 seconds for a
 * quick-add note, longer for a whole recipe, which measured about 45 seconds on its own.
 */
@Component
public class MealsModel {

  static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);
  static final Duration LONG_CALL_TIMEOUT = Duration.ofSeconds(90);
  private static final ExecutorService CALLS =
      Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());

  private final ChatModel chatModel;
  private final String model;

  public MealsModel(final ChatModel chatModel,
      @Value("${meals.model:gpt-5.6-luna}") final String model) {
    this.chatModel = chatModel;
    this.model = model;
  }

  /**
   * Asks the model and returns its JSON text.
   *
   * @param system instructions
   * @param user the owner's words (or a page's text)
   * @param image an image to read, or null
   * @param imageType its MIME type
   * @param schema the JSON schema the answer must match
   * @return the model's JSON
   */
  public String structured(final String system, final String user, final byte[] image,
      final String imageType, final String schema) {
    return structured(system, user, image, imageType, schema, CALL_TIMEOUT);
  }

  /** As {@link #structured(String, String, byte[], String, String)}, bounded by {@code timeout}. */
  public String structured(final String system, final String user, final byte[] image,
      final String imageType, final String schema, final Duration timeout) {
    Map<String, Object> metadata = Map.of(LangfuseContentObservationFilter.SUPPRESS_CONTENT, true);
    UserMessage.Builder userMessage = UserMessage.builder()
        .text(user == null || user.isBlank() ? "Read the attached image." : user)
        .metadata(metadata);
    if (image != null) {
      userMessage.media(new Media(MimeTypeUtils.parseMimeType(imageType),
          new ByteArrayResource(image)));
    }
    OpenAiChatOptions options = OpenAiChatOptions.builder()
        .model(model)
        .reasoningEffort("none")
        .timeout(timeout)
        .responseFormat(OpenAiChatModel.ResponseFormat.builder()
            .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(schema)
            .strict(true)
            .build())
        .build();
    Prompt prompt = new Prompt(List.of(
        SystemMessage.builder().text(system).metadata(metadata).build(), userMessage.build()),
        options);
    ChatResponse response = call(prompt, timeout);
    String text = response == null || response.getResult() == null
        || response.getResult().getOutput() == null ? null
        : response.getResult().getOutput().getText();
    if (text == null || text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The model gave no answer");
    }
    return text;
  }

  private ChatResponse call(final Prompt prompt, final Duration timeout) {
    Future<ChatResponse> pending = CALLS.submit(() -> chatModel.call(prompt));
    try {
      return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException slow) {
      pending.cancel(true);
      throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
          "Reading that took too long. Try again.");
    } catch (InterruptedException interrupted) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Reading was stopped");
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(failed.getCause());
    }
  }
}
