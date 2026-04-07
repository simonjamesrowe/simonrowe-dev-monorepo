package com.simonrowe.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class SecurityConfigTest extends AbstractIntegrationTest {

  @Test
  void publicEndpointIsAccessibleWithoutAuth() throws Exception {
    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk());
  }

  @Test
  void adminEndpointRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/admin/blogs"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminEndpointIsAccessibleWithJwt() throws Exception {
    mockMvc.perform(get("/api/admin/blogs")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk());
  }
}
