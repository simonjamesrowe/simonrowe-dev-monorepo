package com.simonrowe.factory.deploy.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.DeployProgress;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The manual redeploy endpoint. It takes a full commit and nothing else: a branch name or a short
 * sha would let a request mean something different by the time the deployer acted on it.
 */
class DeployControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String PATH = "/api/deploys";
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

  private final DeployWorkflowService service = mock(DeployWorkflowService.class);

  @Test
  void startsDeployForFullCommit() throws Exception {
    when(service.start(SHA, DeployRequest.TRIGGER_MANUAL, null))
        .thenReturn(new DeployAccepted("deploy-prod", "run-1", SHA));

    mvc().perform(
            post(PATH)
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sha\":\"" + SHA + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value("deploy-prod"))
        .andExpect(jsonPath("$.sha").value(SHA));
  }

  @Test
  void normalisesTheCommitToLowerCase() throws Exception {
    when(service.start(SHA, DeployRequest.TRIGGER_MANUAL, null))
        .thenReturn(new DeployAccepted("deploy-prod", "run-1", SHA));

    mvc().perform(
            post(PATH)
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sha\":\"" + SHA.toUpperCase(java.util.Locale.ROOT) + "\"}"))
        .andExpect(status().isAccepted());

    verify(service).start(SHA, DeployRequest.TRIGGER_MANUAL, null);
  }

  @Test
  void refusesDeployWithoutTheToken() throws Exception {
    mvc().perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sha\":\"" + SHA + "\"}"))
        .andExpect(status().isUnauthorized());

    verify(service, never()).start(anyString(), anyString(), any());
  }

  @Test
  void refusesAnythingThatIsNotFullCommit() throws Exception {
    for (String candidate : new String[] {"main", "0123456", "", "not-a-sha"}) {
      mvc().perform(
              post(PATH)
                  .header("X-Factory-Token", TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"sha\":\"" + candidate + "\"}"))
          .andExpect(status().isBadRequest());
    }

    verify(service, never()).start(anyString(), anyString(), any());
  }

  @Test
  void reportsProgressForTheFixedDeployWorkflow() throws Exception {
    // The workflow id is the fixed deploy-prod, so there is exactly one current deploy to report.
    when(service.progress())
        .thenReturn(new DeployProgress(DeployPhase.RECREATE, "Recreating backend", SHA));

    mvc().perform(get(PATH + "/current").header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("RECREATE"))
        .andExpect(jsonPath("$.sha").value(SHA));
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new DeployController(authenticator(), service)).build();
  }

  private static FactoryTokenAuthenticator authenticator() {
    return new FactoryTokenAuthenticator(
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(TOKEN, null),
            "https://temporal.test"));
  }
}
