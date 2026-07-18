package com.simonrowe.webfetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.chat.ChatStreamPublisher;
import com.simonrowe.chat.FetchUrlTools;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class FetchUrlToolsTest {

  private final ChatStreamPublisher publisher = mock(ChatStreamPublisher.class);

  private ToolContext ctx() {
    return new ToolContext(Map.of("sessionId", "s1"));
  }

  @Test
  void returnsFetchedContentAndPublishesLabels() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    when(fetcher.fetch("https://boards.greenhouse.io/acme/jobs/1"))
        .thenReturn(new WebPageContent("Head of Eng", "https://boards.greenhouse.io/acme/jobs/1",
            "Lead a team of engineers."));
    FetchUrlTools tools = new FetchUrlTools(fetcher, publisher);

    Object result = tools.fetchUrl("https://boards.greenhouse.io/acme/jobs/1", ctx());

    assertThat(result).isInstanceOf(WebPageContent.class);
    assertThat(((WebPageContent) result).title()).isEqualTo("Head of Eng");
    verify(publisher).toolStart("s1", "Reading the job posting");
    verify(publisher).toolEnd("s1", "Reading the job posting");
  }

  @Test
  void blankUrlReturnsMessageWithoutFetching() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    FetchUrlTools tools = new FetchUrlTools(fetcher, publisher);

    Object result = tools.fetchUrl("   ", ctx());

    assertThat(result).isEqualTo("I couldn't read that page.");
    verify(fetcher, never()).fetch(anyString());
    verify(publisher, never()).toolStart(any(), any());
  }

  @Test
  void unreadableUrlDegradesGracefully() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    when(fetcher.fetch(anyString())).thenReturn(null);
    FetchUrlTools tools = new FetchUrlTools(fetcher, publisher);

    Object result = tools.fetchUrl("https://www.linkedin.com/jobs/view/999", ctx());

    assertThat(result).isEqualTo("I couldn't read that page.");
    verify(publisher).toolStart("s1", "Reading the job posting");
    verify(publisher).toolEnd("s1", "Reading the job posting");
  }

  @Test
  void missingSessionIdSkipsLabels() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    when(fetcher.fetch(anyString()))
        .thenReturn(new WebPageContent("t", "https://example.com", "body"));
    FetchUrlTools tools = new FetchUrlTools(fetcher, publisher);

    Object result = tools.fetchUrl("https://example.com", new ToolContext(Map.of()));

    assertThat(result).isInstanceOf(WebPageContent.class);
    verify(publisher, never()).toolStart(any(), any());
    verify(publisher, never()).toolEnd(any(), any());
  }
}
