package com.simonrowe.chat;

import com.simonrowe.mcp.ProfileMcpTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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
  public ChatClient chatClient(final ChatClient.Builder builder,
      final ChatMemory chatMemory, final ProfileMcpTools profileMcpTools,
      final VectorStore vectorStore) {
    return builder
        .defaultSystem(systemPrompt + "\n\n"
            + "When you call the skills, jobs, code example, or blog tools, "
            + "the visitor already sees a visual card with the details. Add a brief "
            + "framing sentence and do not re-list the data the card shows.")
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            ContextAwareQuestionAnswerAdvisor.builder(vectorStore, chatMemory)
                .searchRequest(SearchRequest.builder()
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .topK(TOP_K)
                    .build())
                .build()
        )
        .defaultTools(profileMcpTools)
        .build();
  }
}
