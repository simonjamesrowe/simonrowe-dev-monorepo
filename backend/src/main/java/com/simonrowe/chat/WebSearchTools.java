package com.simonrowe.chat;

import com.simonrowe.websearch.SearxngClient;
import com.simonrowe.websearch.WebSearchResult;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Live web search tool for the chat assistant. Kept separate from the large {@code
 * ProfileMcpTools} and registered alongside it. Degrades gracefully: when no SearXNG instance is
 * configured, the query is blank, or the search fails, it returns a short message rather than
 * throwing, so the overall answer is never broken.
 */
@Component
public class WebSearchTools {

  private static final Logger LOG = LoggerFactory.getLogger(WebSearchTools.class);
  private static final String WEB_SEARCH_LABEL = "Searching the web";
  private static final String UNAVAILABLE = "Web search is currently unavailable.";

  private final SearxngClient searxngClient;
  private final ChatStreamPublisher streamPublisher;

  public WebSearchTools(
      final SearxngClient searxngClient, final ChatStreamPublisher streamPublisher) {
    this.searxngClient = searxngClient;
    this.streamPublisher = streamPublisher;
  }

  @WithSpan
  @Tool(
      description =
          "Search the live web for current information about companies Simon has worked at, "
              + "technologies/skills he lists, or sources in his content. Also use it for "
              + "recruiter/employer questions to find current UK job openings comparable to "
              + "Simon's profile (e.g. on LinkedIn, Indeed, Reed, Totaljobs, CV-Library, "
              + "Glassdoor) — include a UK site or location term in the query. Use ONLY to "
              + "enrich topics grounded in Simon's profile/experience/skills — not for general "
              + "or unrelated questions. Cite the results as inline markdown links. Returns a "
              + "list of results with title, url, and snippet.")
  public Object webSearch(
      @ToolParam(
              description =
                  "Search query grounded in Simon's profile, experience, or skills")
          final String query,
      final ToolContext toolContext) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    if (!searxngClient.isConfigured()) {
      LOG.warn("Web search requested but no SearXNG instance is configured.");
      return UNAVAILABLE;
    }

    final String sessionId = sessionId(toolContext);
    publishToolStart(sessionId);
    try {
      return searxngClient.search(query);
    } catch (Exception e) {
      LOG.warn("Web search failed for query: {}", query, e);
      return UNAVAILABLE;
    } finally {
      publishToolEnd(sessionId);
    }
  }

  private static String sessionId(final ToolContext toolContext) {
    if (toolContext == null) {
      return null;
    }
    Object value = toolContext.getContext().get("sessionId");
    return value instanceof String id && !id.isBlank() ? id : null;
  }

  private void publishToolStart(final String sessionId) {
    if (sessionId != null) {
      streamPublisher.toolStart(sessionId, WEB_SEARCH_LABEL);
    }
  }

  private void publishToolEnd(final String sessionId) {
    if (sessionId != null) {
      streamPublisher.toolEnd(sessionId, WEB_SEARCH_LABEL);
    }
  }
}
