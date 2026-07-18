package com.simonrowe.chat;

import com.simonrowe.webfetch.UrlFetcher;
import com.simonrowe.webfetch.WebPageContent;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Reads the contents of a specific web page the visitor references — most often a job posting a
 * recruiter pastes — so the assistant can assess Simon's fit or enrich a grounded answer.
 * Degrades gracefully: blank/invalid/unreadable URLs return a short message rather than throwing.
 */
@Component
public class FetchUrlTools {

  private static final Logger LOG = LoggerFactory.getLogger(FetchUrlTools.class);
  private static final String LABEL = "Reading the job posting";
  private static final String UNREADABLE = "I couldn't read that page.";

  private final UrlFetcher urlFetcher;
  private final ChatStreamPublisher streamPublisher;

  public FetchUrlTools(final UrlFetcher urlFetcher, final ChatStreamPublisher streamPublisher) {
    this.urlFetcher = urlFetcher;
    this.streamPublisher = streamPublisher;
  }

  @WithSpan
  @Tool(
      description =
          "Read the contents of a specific web page the visitor references — most often a job "
              + "posting they paste — to assess Simon's fit for a role or to enrich an answer "
              + "grounded in his profile/experience/skills. Not a general web reader; do not use "
              + "it for unrelated pages. Returns the page title, url, and extracted text, or a "
              + "short message if the page cannot be read (some job boards block automated reads).")
  public Object fetchUrl(
      @ToolParam(description = "The absolute http(s) URL of the page to read")
          final String url,
      final ToolContext toolContext) {
    if (url == null || url.isBlank()) {
      return UNREADABLE;
    }
    final String sessionId = sessionId(toolContext);
    publishToolStart(sessionId);
    try {
      final WebPageContent content = urlFetcher.fetch(url);
      if (content == null) {
        return UNREADABLE;
      }
      return content;
    } catch (Exception e) {
      LOG.warn("fetchUrl failed for {}", url, e);
      return UNREADABLE;
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
      streamPublisher.toolStart(sessionId, LABEL);
    }
  }

  private void publishToolEnd(final String sessionId) {
    if (sessionId != null) {
      streamPublisher.toolEnd(sessionId, LABEL);
    }
  }
}
