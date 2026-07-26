package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.mcp.ProfileMcpTools;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Guards the riskiest part of the tool-call counting change: {@link ChatConfig}'s {@link
 * ToolCallingManager} bean replaces Spring AI's autoconfigured one. That only works because
 * {@code ToolCallingAutoConfiguration}'s bean method is {@code @ConditionalOnMissingBean}. If a
 * Spring AI upgrade dropped that condition, both beans would register and injection would fail as
 * ambiguous; if our bean method stopped matching by type, the autoconfigured one would silently win
 * and {@code tool-call-count} would go back to being permanently zero. Either regression is caught
 * here, at unit-test speed, without needing the full application context.
 */
class ToolCallingManagerOverrideTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
      .withUserConfiguration(ChatConfig.class)
      .withBean(ToolCallCounter.class)
      // ChatConfig also declares the chatClient bean, which is irrelevant here but must be
      // satisfiable for the context to start. Deep stubs cover its fluent builder chain.
      .withBean(ChatClient.Builder.class,
          () -> mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS))
      .withBean(ProfileMcpTools.class, () -> mock(ProfileMcpTools.class))
      .withBean(WebSearchTools.class, () -> mock(WebSearchTools.class))
      .withBean(FetchUrlTools.class, () -> mock(FetchUrlTools.class))
      .withBean(VectorStore.class, () -> mock(VectorStore.class))
      .withBean(ChatModel.class, () -> mock(ChatModel.class))
      .withBean(GuardrailVerdictRegistry.class);

  @Test
  void registersExactlyOneToolCallingManagerAndItIsTheCountingOne() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeanNamesForType(ToolCallingManager.class)).hasSize(1);
      assertThat(context.getBean(ToolCallingManager.class))
          .isInstanceOf(CountingToolCallingManager.class);
    });
  }

  @Test
  void theContextWiredManagerCountsAgainstTheContextWiredCounter() {
    contextRunner.run(context -> {
      ToolCallingManager manager = context.getBean(ToolCallingManager.class);
      ToolCallCounter counter = context.getBean(ToolCallCounter.class);

      ToolCallingChatOptions options = ToolCallingChatOptions.builder()
          .toolContext(Map.of("sessionId", "s1"))
          .build();
      ChatResponse response = ChatResponse.builder()
          .generations(List.of(new Generation(AssistantMessage.builder()
              .content("")
              .properties(Map.of())
              .toolCalls(List.of(
                  new AssistantMessage.ToolCall("id-1", "function", "getJobs", "{}")))
              .build())))
          .build();

      // Counting happens before delegation, so the real DefaultToolCallingManager delegate is
      // free to fail on the unresolvable "getJobs" tool — the count must already be recorded.
      // This proves the wiring, not just the bean type: the manager the context handed out
      // increments the very ToolCallCounter bean that ChatTurnTracer reads from.
      try {
        manager.executeToolCalls(new Prompt("Jobs?", options), response);
      } catch (RuntimeException expected) {
        // Tool resolution is out of scope here; only the counting side effect matters.
      }

      assertThat(counter.takeCount("s1")).isEqualTo(1);
    });
  }
}
