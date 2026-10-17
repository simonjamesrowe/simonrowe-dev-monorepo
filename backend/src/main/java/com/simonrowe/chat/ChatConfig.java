package com.simonrowe.chat;

import com.simonrowe.mcp.ProfileMcpTools;
import com.simonrowe.webfetch.UrlFetcher;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatConfig {

  private static final double SIMILARITY_THRESHOLD = 0.3;
  private static final int TOP_K = 8;

  @Value("${chat.system-prompt:You are a helpful assistant.}")
  private String systemPrompt;

  /**
   * The portfolio chat's conversation memory.
   *
   * <p>{@code @Primary} because Term Time publishes a second {@link ChatMemory} — see
   * {@code SchoolConfiguration.schoolChatMemory}, which is bounded because its endpoint is
   * unauthenticated. Without this the two beans resolve only by the injection points happening
   * to name their parameter {@code chatMemory}, which is true today and is not a thing to rest
   * on. Anything wanting the school's memory must ask for it by qualifier.
   *
   * <p>Eviction here is {@code ChatSessionCleanupService}'s job, not the store's.
   *
   * @return the portfolio chat memory
   */
  @Bean
  @Primary
  public ChatMemory chatMemory() {
    return new ToolFilteringChatMemory(
        MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build());
  }

  @Bean
  public UrlFetcher urlFetcher(
      @Value("${web-fetch.max-chars:8000}") final int maxChars,
      @Value("${web-fetch.timeout-seconds:8}") final int timeoutSeconds) {
    return new UrlFetcher(maxChars, timeoutSeconds);
  }

  /**
   * Overrides the autoconfigured {@link ToolCallingManager} so tool calls can be counted for
   * {@code tool-call-count} Langfuse scoring. {@code ToolCallingAutoConfiguration}'s own bean
   * method is {@code @ConditionalOnMissingBean}, so defining this bean makes Spring AI back off
   * in favour of it rather than both being registered.
   *
   * <p>The delegate is built exactly as the autoconfiguration builds its own bean, so this must
   * be kept in sync with Spring AI's {@code ToolCallingAutoConfiguration} across upgrades.
   */
  @Bean
  public ToolCallingManager toolCallingManager(final ToolCallbackResolver toolCallbackResolver,
      final ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
      final ObjectProvider<ObservationRegistry> observationRegistry,
      final ObjectProvider<ToolCallingObservationConvention> observationConvention,
      final ToolCallCounter toolCallCounter) {
    var toolCallingManager = ToolCallingManager.builder()
        .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
        .toolCallbackResolver(toolCallbackResolver)
        .toolExecutionExceptionProcessor(toolExecutionExceptionProcessor)
        .build();
    observationConvention.ifAvailable(toolCallingManager::setObservationConvention);
    return new CountingToolCallingManager(toolCallingManager, toolCallCounter);
  }

  @Bean
  public ChatClient chatClient(final ChatClient.Builder builder,
      final ChatMemory chatMemory, final ProfileMcpTools profileMcpTools,
      final WebSearchTools webSearchTools, final FetchUrlTools fetchUrlTools,
      final VectorStore vectorStore, final ChatModel chatModel,
      final GuardrailVerdictRegistry verdictRegistry) {
    return builder
        .defaultOptions(chatOptions())
        .defaultSystem(systemPrompt + "\n\n" + widgetPromptGuidance())
        .defaultAdvisors(
            new GuardrailAdvisor(chatModel, verdictRegistry),
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            ContextAwareQuestionAnswerAdvisor.builder(vectorStore, chatMemory)
                .searchRequest(SearchRequest.builder()
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .topK(TOP_K)
                    .build())
                .build()
        )
        .defaultTools(profileMcpTools, webSearchTools, fetchUrlTools)
        .build();
  }

  /**
   * The portfolio chat's own options, scoped to this client rather than set under
   * {@code spring.ai.openai.chat}.
   *
   * <p>{@code reasoningEffort("none")} is required, not tuning. The chat model is
   * {@code gpt-6-luna} since October 2026, which reasons by default, and OpenAI rejects function
   * tools alongside any other effort on {@code /v1/chat/completions} ("To use function tools,
   * use /v1/responses or set reasoning_effort to 'none'"). This chat always offers tools, so
   * every turn would 400. It cannot go in {@code application.yml}: those defaults merge into
   * every per-call {@code OpenAiChatOptions} in the application, including
   * {@link GuardrailAdvisor}'s {@code gpt-4o-mini} classifier, which rejects the argument
   * outright and would leave the topic gate silently open. The model itself still comes from
   * {@code spring.ai.openai.chat.model}.
   *
   * @return the options builder the chat client starts every request from
   */
  static OpenAiChatOptions.Builder chatOptions() {
    return OpenAiChatOptions.builder().reasoningEffort("none");
  }

  static String widgetPromptGuidance() {
    return """
        For any question about Simon's blogs, jobs, skills, code, news, or events, \
        ALWAYS call the matching tool (getRecentBlogs, searchBlogs, getJobs, getSkills, \
        getCodeExamples, searchNews, getUpcomingEvents) — even if the retrieved context \
        already appears to answer it. The tool is what renders the visual card for the \
        visitor; answering from retrieved context alone leaves them with no card.
        
        When you call the skills, jobs, code example, blog, news, or event tools, \
        the visitor already sees a visual card with the details. Add a brief \
        framing sentence and do not re-list the data the card shows.
        
        Link the content you mention so the visitor can explore it, using ONLY URLs \
        and ids present in the tool results or retrieval context. ALWAYS use markdown \
        link syntax [visible name](url) — NEVER paste a bare/raw URL into your prose, \
        because a bare URL is not clickable and looks broken:
        - Blog post: [Blog Title](/blogs/{id}) using the post id.
        - A specific role or job: [Company or Role](/about?job={id}) using the \
        job id — e.g. [Y-Tree](/about?job=5eedd4803c8d74001e4497f5).
        - A specific skill group: [Skill Group](/about?skillGroup={id}) using the \
        group id.
        - Simon's experience in general: [Experience](/about#roles); his profile: \
        [About](/about).
        - News article or event: [Title](originalUrl) using its external URL.
        - Embed an image ONLY with markdown image syntax ![alt](imageUrl) and ONLY \
        using an image URL you were explicitly given (blog/news images); skills and \
        jobs have no images.
        Never invent, guess, or construct a URL or id you were not given. If you have \
        no URL or id for something, mention it in plain text and link nothing.""";
  }
}
