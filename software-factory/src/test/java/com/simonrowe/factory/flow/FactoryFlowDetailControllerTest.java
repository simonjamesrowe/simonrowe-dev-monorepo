package com.simonrowe.factory.flow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unlike {@link FactoryFlowController}, this endpoint carries pull request titles and Linear
 * ticket subjects, so it must reject both a missing and a wrong token — see {@link
 * FactoryFlowController}'s Javadoc for why the two controllers have different postures despite
 * sharing a base path.
 */
class FactoryFlowDetailControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String PATH = "/api/factory/flow/logwatch";

  private final FactoryFlowDetailService service = mock(FactoryFlowDetailService.class);

  @Test
  void refusesTheRequestWithNoTokenAtAll() throws Exception {
    mvc().perform(get(PATH))
        .andExpect(status().isUnauthorized());

    verify(service, never()).detail("logwatch");
  }

  @Test
  void refusesTheRequestWithTheWrongToken() throws Exception {
    mvc().perform(get(PATH).header("X-Factory-Token", "not-the-token"))
        .andExpect(status().isUnauthorized());

    verify(service, never()).detail("logwatch");
  }

  @Test
  void servesTheNodesDetailWithTheCorrectToken() throws Exception {
    when(service.detail("logwatch")).thenReturn(
        new FlowDetail("logwatch", List.of(
            new FlowDetail.Item("logwatch-1", "logwatch-1", "COMPLETED", null, null))));

    mvc().perform(get(PATH).header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodeKey").value("logwatch"))
        .andExpect(jsonPath("$.items[0].id").value("logwatch-1"));
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(
        new FactoryFlowDetailController(authenticator(), service)).build();
  }

  private static FactoryTokenAuthenticator authenticator() {
    return new FactoryTokenAuthenticator(
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(TOKEN),
            "https://temporal.test"));
  }
}
