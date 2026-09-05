package com.simonrowe.factory.platformbackup.api;

import static org.mockito.ArgumentMatchers.anyBoolean;
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
import com.simonrowe.factory.platformbackup.workflow.PlatformBackupProgress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/** The manual half of platform backup: dry run, confirmed real capture, and no restore. */
class PlatformBackupControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String PATH = "/api/platform-backups";

  private final PlatformBackupWorkflowService service =
      mock(PlatformBackupWorkflowService.class);

  @Test
  void startsDryRun() throws Exception {
    when(service.start(true))
        .thenReturn(
            new PlatformBackupAccepted(
                "platform-backup-manual", "run-1", "Platform backup dry run accepted"));

    mvc().perform(
            post(PATH)
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.detail").value("Platform backup dry run accepted"));
  }

  @Test
  void startsConfirmedRealCapture() throws Exception {
    when(service.start(false))
        .thenReturn(
            new PlatformBackupAccepted(
                "platform-backup-manual", "run-2", "Platform backup accepted"));

    mvc().perform(
            post(PATH)
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":false}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.detail").value("Platform backup accepted"));
  }

  @Test
  void refusesCaptureWithoutTheToken() throws Exception {
    mvc().perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}"))
        .andExpect(status().isUnauthorized());

    verify(service, never()).start(anyBoolean());
  }

  @Test
  void surfacesCollisionAsConflict() throws Exception {
    // Two captures writing the same archive would race, and the retention rule deletes past the
    // newest seven — so a duplicate is not merely wasteful, it shortens the recovery window.
    when(service.start(false))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "already running"));

    mvc().perform(
            post(PATH)
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":false}"))
        .andExpect(status().isConflict());
  }

  @Test
  void reportsProgressForTheCurrentCapture() throws Exception {
    when(service.progress())
        .thenReturn(new PlatformBackupProgress("running", "Capturing ClickHouse", true));

    mvc().perform(get(PATH + "/current").header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("running"))
        .andExpect(jsonPath("$.dryRun").value(true));
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(
            new PlatformBackupController(authenticator(), service))
        .build();
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
