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
   * The bulk ready-narration lookup is public for the same reason
   * {@code GET /api/news/summaries/ids} is: the audio is globally shared, so which items have it
   * is not per-reader information — and a signed-out reader has to be able to see the duration on
   * a card and press play.
   */
  @Test
  void readyNarrationsLookupIsPublicBecauseTheAudioIsGloballyShared() throws Exception {
    mockMvc.perform(get("/api/narrations/ready").param("contentType", "BLOG"))
        .andExpect(status().isOk());
  }

  /**
   * The blog narration {@code POST} was public until the listing pages gained a Listen
   * control on every card. It spends from the same monthly text-to-speech budget as summary
   * narration, so gating only the new surface would have left the identical post anonymously
   * narratable from its detail page. The two are now gated alike, and this asserts it —
   * anyone tempted to restore the old asymmetry has to change this test to do it.
   */
  @Test
  void blogNarrationPostRejectsAnonymousBecauseItSpendsOnTheTtsBudget() throws Exception {
    mockMvc.perform(post("/api/blogs/missing-blog/narration"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Any valid JWT is enough — no admin role, exactly as summary narration works. The blog
   * does not exist, so a 404 proves the request reached the controller rather than being
   * turned away by the filter chain.
   */
  @Test
  void blogNarrationPostAllowsAnyAuthenticatedCallerWithoutAnAdminRole() throws Exception {
    mockMvc.perform(post("/api/blogs/missing-blog/narration")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNotFound());
  }

  /**
   * Reads stay public on both narration endpoints: the audio is globally shared content, and
   * a signed-out reader has to be able to play what already exists.
   */
  @Test
  void blogNarrationStatusReadStaysPublic() throws Exception {
    // 404 for a missing blog, but crucially not 401.
    mockMvc.perform(get("/api/blogs/missing-blog/narration"))
        .andExpect(status().isNotFound());
  }

  @Test
  void platformStatusIsPublic() throws Exception {
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk());
  }

  @Test
  void platformReleasesArePublic() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk());
  }
}
