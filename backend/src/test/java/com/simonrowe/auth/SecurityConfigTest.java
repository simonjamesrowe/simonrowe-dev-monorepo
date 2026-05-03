package com.simonrowe.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SecurityConfigTest extends AbstractIntegrationTest {

  private static final SimpleGrantedAuthority ADMIN_AUTHORITY =
      new SimpleGrantedAuthority("ROLE_DEV_PORTAL_ADMIN");
  private static final SimpleGrantedAuthority OTHER_AUTHORITY =
      new SimpleGrantedAuthority("ROLE_VIEWER");

  @Test
  void publicEndpointIsAccessibleWithoutAuth() throws Exception {
    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk());
  }

  @Test
  void adminEndpointRejectsAnonymous() throws Exception {
    mockMvc.perform(get("/api/admin/blogs"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminEndpointRejectsAuthenticatedWithoutAnyRole() throws Exception {
    mockMvc.perform(get("/api/admin/blogs")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminEndpointRejectsAuthenticatedWithDifferentRole() throws Exception {
    mockMvc.perform(get("/api/admin/blogs")
            .with(jwt()
                .jwt(j -> j.subject("test-user"))
                .authorities(OTHER_AUTHORITY)))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminEndpointAllowsAuthenticatedWithAdminRole() throws Exception {
    mockMvc.perform(get("/api/admin/blogs")
            .with(jwt()
                .jwt(j -> j.subject("test-user"))
                .authorities(ADMIN_AUTHORITY)))
        .andExpect(status().isOk());
  }
}
