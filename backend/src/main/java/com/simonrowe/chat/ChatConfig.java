package com.simonrowe.chat;

import com.simonrowe.mcp.ProfileMcpTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

  @Value("${chat.system-prompt:You are a helpful assistant.}")
  private String systemPrompt;

  @Bean
  public ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder()
        .maxMessages(20)
        .build();
  }

  @Bean
  public ChatClient chatClient(final ChatClient.Builder builder,
      final ChatMemory chatMemory, final ProfileMcpTools profileMcpTools) {
    return builder
        .defaultSystem(systemPrompt)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .defaultTools(profileMcpTools)
        .build();
  }
}
