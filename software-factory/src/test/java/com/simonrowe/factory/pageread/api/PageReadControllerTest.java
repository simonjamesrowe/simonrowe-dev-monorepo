package com.simonrowe.factory.pageread.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.pageread.config.PageReadProperties;
import com.simonrowe.factory.pageread.domain.PageReadOutcome;
import com.simonrowe.factory.pageread.domain.PageReadResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Not routed by nginx, but this process also terminates the public webhook. */
class PageReadControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String URL = "https://openai.com/index/scaling-git-at-openai/";

  private final PageReadService service = mock(PageReadService.class);

  private MockMvc mvc(final boolean enabled) {
    CodeReviewProperties properties =
        new CodeReviewProperties(null, null, new CodeReviewProperties.Api(TOKEN, null), null);
    return MockMvcBuilders.standaloneSetup(new PageReadController(
            new FactoryTokenAuthenticator(properties),
            new PageReadProperties(enabled, Duration.ofSeconds(7)),
            service))
        .build();
  }

  private ResultActions read(final MockMvc mvc, final String token, final String body)
      throws Exception {
    var request = post("/api/page-reads").contentType(MediaType.APPLICATION_JSON).content(body);
    if (token != null) {
      request.header("X-Factory-Token", token);
    }
    return mvc.perform(request);
  }

  @Test
  void readsWithTheConfiguredWaitAndReturnsTheResult() throws Exception {
    when(service.read(URL, Duration.ofSeconds(7))).thenReturn(new PageReadResponse(
        PageReadState.COMPLETED,
        new PageReadResult(PageReadOutcome.READ, 200, URL, "Scaling Git at OpenAI", "Body.")));

    read(mvc(true), TOKEN, "{\"url\":\"%s\"}".formatted(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("COMPLETED"))
        .andExpect(jsonPath("$.result.outcome").value("READ"))
        .andExpect(jsonPath("$.result.text").value("Body."));
  }

  @Test
  void refusesMissingOrWrongToken() throws Exception {
    read(mvc(true), null, "{\"url\":\"%s\"}".formatted(URL)).andExpect(status().isUnauthorized());
    read(mvc(true), "wrong", "{\"url\":\"%s\"}".formatted(URL))
        .andExpect(status().isUnauthorized());
    verify(service, never()).read(anyString(), any());
  }

  @Test
  void answers503WhileDisabled() throws Exception {
    read(mvc(false), TOKEN, "{\"url\":\"%s\"}".formatted(URL))
        .andExpect(status().isServiceUnavailable());
    verify(service, never()).read(anyString(), any());
  }

  @Test
  void refusesAnythingButAnAbsoluteHttpUrl() throws Exception {
    for (String body : new String[] {
        "{}", "{\"url\":\"\"}", "{\"url\":\"/index/relative\"}",
        "{\"url\":\"file:///etc/passwd\"}", "{\"url\":\"javascript:alert(1)\"}",
        "{\"url\":\"https://\"}",
        "{\"url\":\"https://example.com/%s\"}".formatted("x".repeat(2_100))}) {
      read(mvc(true), TOKEN, body).andExpect(status().isBadRequest());
    }
    verify(service, never()).read(anyString(), any());
  }
}
