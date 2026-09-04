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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code /api/factory/flow} carries Linear ticket counts and pull request figures — a different
 * disclosure class from {@code /api/factory/status}'s booleans and poller counts — so it must be
 * token-protected.
 *
 * <p>{@link FactoryTokenAuthenticator} selects nothing by path: there is no security filter chain
 * and no prefix allowlist anywhere in this module. Every protected endpoint enrols itself by
 * taking an {@code X-Factory-Token} header and calling {@code authenticate} directly — see
 * {@code FactoryRunController} and {@code DeployController}. This controller does the same, so
 * the only way to prove the path is covered is to exercise it end to end, which is what
 * {@link #refusesAnUnauthenticatedRequest()} does; the request-mapping value alone would prove
 * nothing about enforcement.
 */
class FactoryFlowControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String PATH = "/api/factory/flow";

  private final FactoryFlowService service = mock(FactoryFlowService.class);

  @Test
  void servesTheFlowWithTheConfiguredToken() throws Exception {
    FactoryFlowResponse expected = new FactoryFlowResponse(Instant.now(), List.of(), List.of());
    when(service.flow()).thenReturn(expected);

    mvc().perform(get(PATH).header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodes").isArray())
        .andExpect(jsonPath("$.edges").isArray());
  }

  @Test
  void refusesAnUnauthenticatedRequest() throws Exception {
    mvc().perform(get(PATH)).andExpect(status().isUnauthorized());

    verify(service, never()).flow();
  }

  @Test
  void refusesTheWrongToken() throws Exception {
    mvc().perform(get(PATH).header("X-Factory-Token", "wrong"))
        .andExpect(status().isUnauthorized());

    verify(service, never()).flow();
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new FactoryFlowController(authenticator(), service))
        .build();
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
