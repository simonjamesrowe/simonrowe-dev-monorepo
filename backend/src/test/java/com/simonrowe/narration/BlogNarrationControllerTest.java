package com.simonrowe.narration;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BlogNarrationControllerTest {

  private NarrationService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = org.mockito.Mockito.mock(NarrationService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new BlogNarrationController(service)).build();
  }

  @Test
  void anonymousGetReturnsReadyAudioWithoutInternalFields() throws Exception {
    when(service.getStatus(NarrationContentType.BLOG, "blog-1", null, 0))
        .thenReturn(new NarrationResponse(
        NarrationResponse.PublicState.READY, 4,
        "/uploads/narrations/id/narration.mp3", 90L, false, "Ready"));

    mockMvc.perform(get("/api/blogs/blog-1/narration"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"))
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.audioUrl")
            .value("/uploads/narrations/id/narration.mp3"))
        .andExpect(jsonPath("$.durationSeconds").value(90))
        .andExpect(jsonPath("$.providerOperationName").doesNotExist())
        .andExpect(jsonPath("$.failureCode").doesNotExist());
  }

  @Test
  void longPollParametersAreForwardedAndNullFieldsAreOmitted() throws Exception {
    when(service.getStatus(NarrationContentType.BLOG, "blog-1", 7L, 25)).thenReturn(
        NarrationResponse.notRequested());

    mockMvc.perform(get("/api/blogs/blog-1/narration")
            .param("afterVersion", "7")
            .param("waitSeconds", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NOT_REQUESTED"))
        .andExpect(jsonPath("$.audioUrl").doesNotExist())
        .andExpect(jsonPath("$.durationSeconds").doesNotExist());
  }

  @Test
  void firstRequestIsAcceptedAndDuplicateReturnsCurrentState() throws Exception {
    NarrationResponse queued = new NarrationResponse(
        NarrationResponse.PublicState.QUEUED, 1, null, null,
        false, "Preparing audio");
    when(service.request(NarrationContentType.BLOG, "new-blog"))
        .thenReturn(new NarrationService.RequestResult(queued, true));
    when(service.request(NarrationContentType.BLOG, "existing-blog"))
        .thenReturn(new NarrationService.RequestResult(queued, false));

    mockMvc.perform(post("/api/blogs/new-blog/narration")
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("QUEUED"));
    mockMvc.perform(post("/api/blogs/existing-blog/narration"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("QUEUED"));
  }

  @Test
  void disabledProviderReturnsServiceUnavailableWithoutRetryDetails() throws Exception {
    when(service.request(NarrationContentType.BLOG, "blog-1")).thenReturn(
        new NarrationService.RequestResult(NarrationResponse.unavailable(), false));

    mockMvc.perform(post("/api/blogs/blog-1/narration"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().doesNotExist("WWW-Authenticate"))
        .andExpect(jsonPath("$.state").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.retryable").value(false));
  }
}
