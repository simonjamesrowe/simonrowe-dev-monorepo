package com.simonrowe.factory.cvefix.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.CveFixPhase;
import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The manual half of issue-only vulnerability scanning. nginx routes neither path, but this JVM
 * terminates the public webhook too, so the token — not the proxy configuration — is the boundary.
 */
class CveScanControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String PATH = "/api/vulnerability-scans";
  private static final String WORKFLOW_ID = "cve-scan-manual-1";

  private final CveScanWorkflowService service = mock(CveScanWorkflowService.class);

  @Test
  void startsScanWhenTheTokenMatches() throws Exception {
    when(service.start())
        .thenReturn(new CveScanAccepted(WORKFLOW_ID, "run-1", "Vulnerability scan accepted"));

    mvc(TOKEN, true)
        .perform(post(PATH).header("X-Factory-Token", TOKEN))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value(WORKFLOW_ID))
        .andExpect(jsonPath("$.runId").value("run-1"));
  }

  @Test
  void refusesScanWithoutTheToken() throws Exception {
    mvc(TOKEN, true).perform(post(PATH)).andExpect(status().isUnauthorized());

    verify(service, never()).start();
  }

  @Test
  void refusesScanWhileTheModuleIsDisabled() throws Exception {
    // Nothing polls the cve-fix queue with the flag off, so accepting the request would leave a
    // workflow sitting in Temporal looking accepted until an activity timeout.
    mvc(TOKEN, false)
        .perform(post(PATH).header("X-Factory-Token", TOKEN))
        .andExpect(status().isServiceUnavailable());

    verify(service, never()).start();
  }

  @Test
  void reportsProgressForAnAcceptedScan() throws Exception {
    when(service.progress(WORKFLOW_ID))
        .thenReturn(new CveFixProgress(CveFixPhase.FILING, "Filing in Linear", 4));

    mvc(TOKEN, true)
        .perform(get(PATH + "/" + WORKFLOW_ID).header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("FILING"))
        .andExpect(jsonPath("$.count").value(4));
  }

  private MockMvc mvc(final String configuredToken, final boolean enabled) {
    CveFixProperties properties =
        new CveFixProperties(
            enabled, null, null, null, null, null, null, null, null, null, null);
    return MockMvcBuilders.standaloneSetup(
            new CveScanController(authenticator(configuredToken), properties, service))
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
