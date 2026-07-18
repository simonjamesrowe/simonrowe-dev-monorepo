package com.simonrowe.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.chat.ChatStreamPublisher;
import com.simonrowe.chat.WebSearchTools;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class WebSearchToolsTest {

  private final ChatStreamPublisher streamPublisher = mock(ChatStreamPublisher.class);

  private ToolContext toolContext() {
    return new ToolContext(Map.of("sessionId", "session-1"));
  }

  @Test
  void mapsResultsFromClient() {
    SearxngClient client = mock(SearxngClient.class);
    when(client.isConfigured()).thenReturn(true);
    when(client.search("Kafka at a company he worked at"))
        .thenReturn(
            List.of(new WebSearchResult("Kafka news", "https://example.com/kafka", "snippet")));
    WebSearchTools tools = new WebSearchTools(client, streamPublisher);

    Object result = tools.webSearch("Kafka at a company he worked at", toolContext());

    assertThat(result).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<WebSearchResult> results = (List<WebSearchResult>) result;
    assertThat(results).hasSize(1);
    assertThat(results.get(0).url()).isEqualTo("https://example.com/kafka");
    verify(streamPublisher).toolStart("session-1", "Searching the web");
    verify(streamPublisher).toolEnd("session-1", "Searching the web");
  }

  @Test
  void blankQueryReturnsEmptyWithoutCallingClient() {
    SearxngClient client = mock(SearxngClient.class);
    WebSearchTools tools = new WebSearchTools(client, streamPublisher);

    Object result = tools.webSearch("   ", toolContext());

    assertThat(result).isEqualTo(List.of());
    verify(client, never()).search(anyString());
    verify(streamPublisher, never()).toolStart(any(), any());
  }

  @Test
  void nullQueryReturnsEmptyWithoutCallingClient() {
    SearxngClient client = mock(SearxngClient.class);
    WebSearchTools tools = new WebSearchTools(client, streamPublisher);

    Object result = tools.webSearch(null, toolContext());

    assertThat(result).isEqualTo(List.of());
    verify(client, never()).search(anyString());
  }

  @Test
  void unconfiguredReturnsUnavailableWithoutCallingClient() {
    SearxngClient client = mock(SearxngClient.class);
    when(client.isConfigured()).thenReturn(false);
    WebSearchTools tools = new WebSearchTools(client, streamPublisher);

    Object result = tools.webSearch("anything", toolContext());

    assertThat(result).isEqualTo("Web search is currently unavailable.");
    verify(client, never()).search(anyString());
    verify(streamPublisher, never()).toolStart(any(), any());
  }

  @Test
  void clientFailureDegradesGracefully() {
    SearxngClient client = mock(SearxngClient.class);
    when(client.isConfigured()).thenReturn(true);
    when(client.search(anyString())).thenThrow(new RuntimeException("timeout"));
    WebSearchTools tools = new WebSearchTools(client, streamPublisher);

    Object result = tools.webSearch("Kafka", toolContext());

    assertThat(result).isEqualTo("Web search is currently unavailable.");
    verify(streamPublisher).toolStart("session-1", "Searching the web");
    verify(streamPublisher).toolEnd("session-1", "Searching the web");
  }

  @Test
  void missingSessionIdSkipsToolLabels() {
    SearxngClient client = mock(SearxngClient.class);
    when(client.isConfigured()).thenReturn(true);
    when(client.search(anyString())).thenReturn(List.of());
    WebSearchTools tools = new WebSearchTools(client, streamPublisher);

    Object result = tools.webSearch("Kafka", new ToolContext(Map.of()));

    assertThat(result).isEqualTo(List.of());
    verify(streamPublisher, never()).toolStart(any(), any());
    verify(streamPublisher, never()).toolEnd(any(), any());
  }
}
