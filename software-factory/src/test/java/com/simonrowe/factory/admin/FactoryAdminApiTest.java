package com.simonrowe.factory.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The two internal read endpoints the admin console depends on, and the deliberate asymmetry
 * between them: status is open on the container network, run progress is not.
 */
class FactoryAdminApiTest {

  private static final String TOKEN = "trigger-token";
  private static final String WORKFLOW_ID = "cve-scan-manual-1";

  private final FactoryStatusService statusService = mock(FactoryStatusService.class);
  private final FactoryRunStatusService runService = mock(FactoryRunStatusService.class);

  @Test
  void servesStatusWithoutToken() throws Exception {
    // Load-bearing: the deployer holds no FACTORY_TRIGGER_TOKEN on purpose, and it is the only
    // authority on the deploy and platform-backup modules. Requiring a token here would make it
    // permanently report itself unreachable.
    when(statusService.status()).thenReturn(
        new FactoryStatusResponse("deployer", Instant.EPOCH, List.of()));

    statusMvc().perform(get("/api/factory/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.container").value("deployer"));
  }

  @Test
  void refusesRunProgressWithoutToken() throws Exception {
    runMvc(TOKEN).perform(get("/api/factory/runs/" + WORKFLOW_ID))
        .andExpect(status().isUnauthorized());

    verify(runService, never()).progress(anyString());
  }

  @Test
  void servesRunProgressWithTheToken() throws Exception {
    when(runService.progress(WORKFLOW_ID)).thenReturn(
        new FactoryRunProgress(
            WORKFLOW_ID, "run-1", "WORKFLOW_EXECUTION_STATUS_RUNNING", "FILING", "Filing", false));

    runMvc(TOKEN)
        .perform(get("/api/factory/runs/" + WORKFLOW_ID).header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("FILING"))
        .andExpect(jsonPath("$.terminal").value(false));
  }

  @Test
  void rejectsMalformedWorkflowIdBeforeAskingTemporal() throws Exception {
    runMvc(TOKEN)
        .perform(get("/api/factory/runs/has spaces").header("X-Factory-Token", TOKEN))
        .andExpect(status().isBadRequest());

    verify(runService, never()).progress(anyString());
  }

  private MockMvc statusMvc() {
    return MockMvcBuilders.standaloneSetup(new FactoryStatusController(statusService)).build();
  }

  private MockMvc runMvc(final String configuredToken) {
    return MockMvcBuilders.standaloneSetup(
            new FactoryRunController(authenticator(configuredToken), runService))
        .build();
  }

  private static FactoryTokenAuthenticator authenticator(final String configuredToken) {
    return new FactoryTokenAuthenticator(
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(configuredToken, null),
            "https://temporal.test"));
  }
}
