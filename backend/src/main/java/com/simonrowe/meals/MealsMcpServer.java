package com.simonrowe.meals;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The private meals MCP server, for Claude Code, at {@code /api/meals/mcp}.
 *
 * <p><strong>Its tools must never be a {@code ToolCallbackProvider} bean.</strong> Spring AI's MCP
 * auto-configuration publishes every such bean on the site's PUBLIC {@code /mcp}, which would
 * serve copyrighted recipes and the owner's kitchen to anyone. This server is built by hand from
 * the MCP SDK's stateless servlet transport and registered as its own servlet, so nothing about
 * it is visible to that auto-configuration; {@code MealsMcpIsolationTest} pins that the public
 * server lists none of these tools.
 *
 * <p>Authentication is a meals personal access token, checked by {@link MealsMcpSecurity}'s
 * chain, which stores the owner on the request; the transport's context extractor carries it
 * into every tool call.
 */
@Configuration
public class MealsMcpServer {

  private static final Logger LOG = LoggerFactory.getLogger(MealsMcpServer.class);
  private static final String OWNER = "ownerId";

  @Bean
  public ServletRegistrationBean<HttpServletStatelessServerTransport> mealsMcpServlet(
      final MealsMcpTools tools) {
    McpJsonMapper json = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
        .jsonMapper(json)
        .messageEndpoint(MealsMcpSecurity.PATH)
        .contextExtractor(request -> {
          Map<String, Object> context = new HashMap<>();
          Object owner = request.getAttribute(MealsMcpSecurity.OWNER_ATTRIBUTE);
          if (owner != null) {
            context.put(OWNER, owner);
          }
          return McpTransportContext.create(context);
        })
        .build();
    // Building the server installs its request handler on the transport, which keeps it alive.
    McpServer.sync(transport)
        .serverInfo("simonrowe-meals", "1.0.0")
        .jsonMapper(json)
        .instructions(MealsMcpTools.INSTRUCTIONS)
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).prompts(true).build())
        .tools(tools.tools().stream().map(t -> tool(json, t)).toList())
        .prompts(tools.prompts().stream().map(MealsMcpServer::prompt).toList())
        .build();
    LOG.info("Meals MCP server ready at {} with {} tools", MealsMcpSecurity.PATH,
        tools.tools().size());
    ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
        new ServletRegistrationBean<>(transport, MealsMcpSecurity.PATH);
    registration.setName("mealsMcp");
    registration.setAsyncSupported(true);
    return registration;
  }

  private static McpStatelessServerFeatures.SyncToolSpecification tool(final McpJsonMapper json,
      final MealsMcpTools.ToolDef def) {
    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name(def.name())
        .description(def.description())
        .inputSchema(json, def.inputSchema())
        .build();
    BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (context, request) -> {
          String ownerId = (String) context.get(OWNER);
          if (ownerId == null) {
            return error("Not authenticated");
          }
          try {
            Map<String, Object> args = request.arguments() == null ? Map.of()
                : request.arguments();
            return McpSchema.CallToolResult.builder()
                .addTextContent(def.handler().apply(ownerId, args))
                .isError(false)
                .build();
          } catch (ResponseStatusException e) {
            return error(e.getReason() == null ? e.getMessage() : e.getReason());
          } catch (IllegalArgumentException e) {
            return error(e.getMessage());
          }
        };
    return McpStatelessServerFeatures.SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(handler)
        .build();
  }

  private static McpStatelessServerFeatures.SyncPromptSpecification prompt(
      final MealsMcpTools.PromptDef def) {
    McpSchema.Prompt prompt = new McpSchema.Prompt(def.name(), def.description(), List.of());
    return new McpStatelessServerFeatures.SyncPromptSpecification(prompt,
        (context, request) -> new McpSchema.GetPromptResult(def.description(), List.of(
            new McpSchema.PromptMessage(McpSchema.Role.USER,
                new McpSchema.TextContent(def.text())))));
  }

  private static McpSchema.CallToolResult error(final String message) {
    return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
  }
}
