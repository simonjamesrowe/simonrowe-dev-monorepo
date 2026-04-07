package com.simonrowe.chat;

import com.simonrowe.mcp.ProfileMcpTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

  private static final double SIMILARITY_THRESHOLD = 0.7;
  private static final int TOP_K = 5;

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
        .defaultSystem(systemPrompt)
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            QuestionAnswerAdvisor.builder(vectorStore)
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
