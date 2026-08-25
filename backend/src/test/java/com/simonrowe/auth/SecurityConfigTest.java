package com.simonrowe.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

  @Test
  void summaryPostRejectsAnonymousBecauseItSpendsOnTheModel() throws Exception {
    mockMvc.perform(post("/api/news/article-1/summary"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void summaryNarrationPostRejectsAnonymousBecauseItSpendsOnTheTtsBudget()
      throws Exception {
    mockMvc.perform(post("/api/news/article-1/summary/narration"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Any valid JWT is enough — no admin role — exactly as favourites writes work. The
   * article does not exist, so a 404 (or any non-401/403) proves the request reached the
   * controller rather than being turned away by the filter chain.
   */
  @Test
  void summaryPostAllowsAnyAuthenticatedCallerWithoutAnAdminRole() throws Exception {
    mockMvc.perform(post("/api/news/missing-article/summary")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void summaryReadsArePublicBecauseTheArtefactIsGloballyShared() throws Exception {
    mockMvc.perform(get("/api/news/summaries/ids"))
        .andExpect(status().isOk());
  }

  @Test
  void summaryStatusReadIsPublic() throws Exception {
    // 404 for a missing article, but crucially not 401.
    mockMvc.perform(get("/api/news/missing-article/summary"))
        .andExpect(status().isNotFound());
  }

  @Test
  void summaryNarrationStatusReadIsPublic() throws Exception {
    mockMvc.perform(get("/api/news/missing-article/summary/narration"))
        .andExpect(status().isNotFound());
  }

  /**
   * The blog narration contract is deliberately frozen: its {@code POST} stays public even
   * though the new summary narration {@code POST} is authenticated. This asserts the
   * asymmetry on purpose, so nobody "harmonises" the two by accident.
   */
  @Test
  void blogNarrationPostRemainsPublic() throws Exception {
    // A missing blog is a 404 from the controller — crucially not the 401 the filter
    // chain would return if this path had been made authenticated.
    mockMvc.perform(post("/api/blogs/missing-blog/narration"))
        .andExpect(status().isNotFound());
  }
}
