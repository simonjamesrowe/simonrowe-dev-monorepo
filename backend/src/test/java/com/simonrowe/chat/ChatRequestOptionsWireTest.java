package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the portfolio chat and its guardrail actually send to OpenAI.
 *
 * <p>Option merging happens in three layers (the model's defaults from
 * {@code spring.ai.openai.chat}, the chat client's {@link ChatConfig#chatOptions()}, and the
 * guardrail's own per-call options), and both ways this goes wrong are silent. Without
 * {@code reasoning_effort: none} gpt-6-luna 400s every tool-carrying chat turn. With it leaking
 * into the guardrail, gpt-4o-mini rejects the call, the advisor fails open, and the topic gate
 * stops gating with nothing but a WARN. So this reads the request bodies off the wire, through a
 * real {@link OpenAiChatModel}, rather than asserting on option objects.
 */
class ChatRequestOptionsWireTest {

  private static final String COMPLETION = """
      {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"stub",\
      "choices":[{"index":0,"message":{"role":"assistant","content":"SAFE"},\
      "finish_reason":"stop"}],\
      "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

  private final ObjectMapper json = new ObjectMapper();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private OpenAiChatModel model;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      final byte[] reply = COMPLETION.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, reply.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(reply);
      }
    });
    server.start();
    // The defaults stand in for spring.ai.openai.chat: the model and nothing else, as shipped.
    model = OpenAiChatModel.builder()
        .options(OpenAiChatOptions.builder()
            .model("gpt-6-luna")
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
            .apiKey("test-key")
            .maxRetries(0)
            .build())
        .build();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private JsonNode requestFor(final String modelName) {
    return bodies.stream().map(json::readTree)
        .filter(body -> modelName.equals(body.path("model").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no request for " + modelName + " in " + bodies));
  }

  @Test
  @DisplayName("the chat sends reasoning_effort none with its tools; the guardrail sends neither")
  void chatAndGuardrailOptionsStaySeparate() {
    final ChatClient chat = ChatClient.builder(model)
        .defaultOptions(ChatConfig.chatOptions())
        .defaultAdvisors(new GuardrailAdvisor(model, new GuardrailVerdictRegistry()))
        .defaultTools(new LookupTool())
        .build();

    assertThat(chat.prompt().user("What has Simon written about Kafka?").call().content())
        .isEqualTo("SAFE");

    final JsonNode turn = requestFor("gpt-6-luna");
    assertThat(turn.path("reasoning_effort").asText()).isEqualTo("none");
    assertThat(turn.path("tools").isArray()).isTrue();
    assertThat(turn.path("tools")).isNotEmpty();

    final JsonNode guardrail = requestFor("gpt-4o-mini");
    assertThat(guardrail.has("reasoning_effort")).isFalse();
    assertThat(guardrail.has("tools")).isFalse();
    assertThat(guardrail.path("temperature").asDouble()).isZero();
    assertThat(bodies).hasSize(2);
  }

  /** One tool, because the reasoning-effort rule only bites on requests that carry tools. */
  static final class LookupTool {

    @Tool(description = "Looks up Simon's blog posts")
    String searchBlogs(final String query) {
      return "none";
    }
  }
}
