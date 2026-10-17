package com.simonrowe.factory.writing.api;

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
import com.simonrowe.factory.writing.config.WritingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Neither endpoint is routed by nginx, but this process also terminates the public webhook, so
 * the token check is what keeps them closed. The work runs on the owner's Claude subscription,
 * which makes an open endpoint here a terms problem as well as a cost one.
 */
class ContentWritingControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String BODY =
      """
      {"jobId":"release-abc123-platform-release-v1","prompt":"Write a note."}
      """;

  private final ContentWritingService service = mock(ContentWritingService.class);

  private MockMvc mvc(final boolean enabled, final int maxPromptChars) {
    CodeReviewProperties properties =
        new CodeReviewProperties(null, null, new CodeReviewProperties.Api(TOKEN, null), null);
    return MockMvcBuilders.standaloneSetup(
            new ContentWritingController(
                new FactoryTokenAuthenticator(properties),
                new WritingProperties(enabled, null, null, null, 0, null, null, maxPromptChars),
                service))
        .build();
  }

  private MockMvc mvc() {
    return mvc(true, 0);
  }

  private ResultActions submit(final MockMvc mvc, final String token, final String body)
      throws Exception {
    var request = post("/api/writing/jobs").contentType(MediaType.APPLICATION_JSON).content(body);
    if (token != null) {
      request.header("X-Factory-Token", token);
    }
    return mvc.perform(request);
  }

  @Test
  void startsNewJobWithAccepted() throws Exception {
    when(service.submit("release-abc123-platform-release-v1", "Write a note."))
        .thenReturn(
            new ContentWritingService.Submission(
                true,
                WritingJobStatus.of(
                    "release-abc123-platform-release-v1", WritingJobState.RUNNING)));

    submit(mvc(), TOKEN, BODY)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"));
  }

  @Test
  void answersAnExistingJobWith200AndItsState() throws Exception {
    // Idempotency is the contract the backend's sweep relies on: it may submit the same job on
    // every tick, and a completed one must come back as its text, not as an error.
    when(service.submit(anyString(), anyString()))
        .thenReturn(
            new ContentWritingService.Submission(
                false,
                new WritingJobStatus(
                    "release-abc123-platform-release-v1", WritingJobState.COMPLETED, "Done.")));

    submit(mvc(), TOKEN, BODY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.text").value("Done."));
  }

  @Test
  void rejectsSubmitWithoutTheToken() throws Exception {
    submit(mvc(), null, BODY).andExpect(status().isUnauthorized());
    submit(mvc(), "wrong", BODY).andExpect(status().isUnauthorized());

    verify(service, never()).submit(anyString(), anyString());
  }

  @Test
  void rejectsStatusReadWithoutTheToken() throws Exception {
    mvc().perform(get("/api/writing/jobs/job-1")).andExpect(status().isUnauthorized());

    verify(service, never()).status(anyString());
  }

  @Test
  void answers503WhenDisabledEvenWithTheToken() throws Exception {
    submit(mvc(false, 0), TOKEN, BODY).andExpect(status().isServiceUnavailable());
    mvc(false, 0)
        .perform(get("/api/writing/jobs/job-1").header("X-Factory-Token", TOKEN))
        .andExpect(status().isServiceUnavailable());

    verify(service, never()).submit(anyString(), anyString());
    verify(service, never()).status(anyString());
  }

  @Test
  void checksTheTokenBeforeSayingItIsDisabled() throws Exception {
    // A caller without the token learns nothing about the module's configuration.
    submit(mvc(false, 0), null, BODY).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsJobIdsOutsideThePlainCharset() throws Exception {
    for (String jobId :
        new String[] {"", "-leading-hyphen", "has space", "slash/inside", "x".repeat(129)}) {
      submit(mvc(), TOKEN, "{\"jobId\":\"%s\",\"prompt\":\"p\"}".formatted(jobId))
          .andExpect(status().isBadRequest());
    }
    submit(mvc(), TOKEN, "{\"prompt\":\"p\"}").andExpect(status().isBadRequest());

    verify(service, never()).submit(anyString(), anyString());
  }

  @Test
  void rejectsMissingOrBlankPrompt() throws Exception {
    submit(mvc(), TOKEN, "{\"jobId\":\"job-1\"}").andExpect(status().isBadRequest());
    submit(mvc(), TOKEN, "{\"jobId\":\"job-1\",\"prompt\":\"  \"}")
        .andExpect(status().isBadRequest());

    verify(service, never()).submit(anyString(), anyString());
  }

  @Test
  void capsThePromptSize() throws Exception {
    String body = "{\"jobId\":\"job-1\",\"prompt\":\"%s\"}";
    submit(mvc(true, 10), TOKEN, body.formatted("x".repeat(11))).andExpect(status().is(413));
    verify(service, never()).submit(anyString(), anyString());

    when(service.submit(anyString(), anyString()))
        .thenReturn(
            new ContentWritingService.Submission(
                true, WritingJobStatus.of("job-1", WritingJobState.RUNNING)));
    submit(mvc(true, 10), TOKEN, body.formatted("x".repeat(10)))
        .andExpect(status().isAccepted());
  }

  @Test
  void readsJobState() throws Exception {
    when(service.status("job-1"))
        .thenReturn(new WritingJobStatus("job-1", WritingJobState.COMPLETED, "The text."));

    mvc()
        .perform(get("/api/writing/jobs/job-1").header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.text").value("The text."));
  }

  @Test
  void rejectsAnInvalidJobIdOnRead() throws Exception {
    mvc()
        .perform(get("/api/writing/jobs/-bad").header("X-Factory-Token", TOKEN))
        .andExpect(status().isBadRequest());

    verify(service, never()).status(anyString());
  }
}
