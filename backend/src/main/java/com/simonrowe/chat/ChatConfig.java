package com.simonrowe.chat;

import com.simonrowe.mcp.ProfileMcpTools;
import com.simonrowe.webfetch.UrlFetcher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

  private static final double SIMILARITY_THRESHOLD = 0.3;
  private static final int TOP_K = 8;

  @Value("${chat.system-prompt:You are a helpful assistant.}")
  private String systemPrompt;

  @Bean
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

  @Bean
  public ChatClient chatClient(final ChatClient.Builder builder,
      final ChatMemory chatMemory, final ProfileMcpTools profileMcpTools,
      final WebSearchTools webSearchTools,
      final VectorStore vectorStore, final ChatModel chatModel) {
    return builder
        .defaultSystem(systemPrompt + "\n\n" + widgetPromptGuidance())
        .defaultAdvisors(
            new GuardrailAdvisor(chatModel),
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            ContextAwareQuestionAnswerAdvisor.builder(vectorStore, chatMemory)
                .searchRequest(SearchRequest.builder()
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .topK(TOP_K)
                    .build())
                .build()
        )
        .defaultTools(profileMcpTools, webSearchTools)
        .build();
  }

  static String widgetPromptGuidance() {
    return "When you call the skills, jobs, code example, blog, news, or event tools, "
        + "the visitor already sees a visual card with the details. Add a brief "
        + "framing sentence and do not re-list the data the card shows.\n\n"
        + "Link the content you mention so the visitor can explore it, using ONLY URLs "
        + "and ids present in the tool results or retrieval context. ALWAYS use markdown "
        + "link syntax [visible name](url) — NEVER paste a bare/raw URL into your prose, "
        + "because a bare URL is not clickable and looks broken:\n"
        + "- Blog post: [Blog Title](/blogs/{id}) using the post id.\n"
        + "- A specific role or job: [Company or Role](/experience?job={id}) using the "
        + "job id — e.g. [Y-Tree](/experience?job=5eedd4803c8d74001e4497f5).\n"
        + "- A specific skill group: [Skill Group](/experience?skillGroup={id}) using the "
        + "group id.\n"
        + "- News article or event: [Title](originalUrl) using its external URL.\n"
        + "- Embed an image ONLY with markdown image syntax ![alt](imageUrl) and ONLY "
        + "using an image URL you were explicitly given (blog/news images); skills and "
        + "jobs have no images.\n"
        + "Never invent, guess, or construct a URL or id you were not given. If you have "
        + "no URL or id for something, mention it in plain text and link nothing.";
  }
}
