package com.simonrowe.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link ProfileMcpTools}'s {@code @Tool} methods over the MCP server transport.
 *
 * <p>The chat client consumes these tools in-process via {@code ChatClient.defaultTools},
 * which does not register them with the MCP server. Publishing a {@link ToolCallbackProvider}
 * bean is the mechanism Spring AI's MCP server auto-configuration uses to surface tool
 * callbacks in {@code tools/list} and {@code tools/call} for external MCP clients.
 */
@Configuration
public class McpServerConfig {

  /**
   * Registers the profile tool methods with the MCP server.
   *
   * @param profileMcpTools the bean holding the annotated tool methods
   * @return a provider exposing those methods as MCP tools
   */
  @Bean
  public ToolCallbackProvider profileMcpToolCallbackProvider(
      final ProfileMcpTools profileMcpTools) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(profileMcpTools)
        .build();
  }
}
