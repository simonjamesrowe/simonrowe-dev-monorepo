package com.simonrowe.coparent.assistant;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Confirms the assistant is independently dark-launched behind its own feature flag. */
@TestPropertySource(properties = {
    "coparent.enabled=true",
    "coparent.assistant.enabled=false"
})
class AssistantDisabledIntegrationTest extends AbstractIntegrationTest {

  @Test
  void reportsUnavailableAndRejectsAnalysis() throws Exception {
    mockMvc.perform(get("/api/coparent/assistant/config")
            .with(jwt().jwt(token -> token.subject("auth0|alice"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled", is(false)));

    mockMvc.perform(multipart(
            "/api/coparent/families/{familyId}/assistant/batches", new ObjectId())
            .param("text", "Create an event")
            .with(jwt().jwt(token -> token.subject("auth0|alice"))))
        .andExpect(status().isNotFound());
  }
}
