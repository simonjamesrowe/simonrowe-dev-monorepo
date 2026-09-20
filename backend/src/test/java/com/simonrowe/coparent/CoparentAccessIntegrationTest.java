package com.simonrowe.coparent;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/** Pins authentication, private caching and malformed-id behaviour at the product boundary. */
@TestPropertySource(properties = "coparent.enabled=true")
class CoparentAccessIntegrationTest extends AbstractIntegrationTest {

  @Test
  void rejectsAnonymousRequests() throws Exception {
    mockMvc.perform(get("/api/coparent/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
  }

  @Test
  void returnsControlledErrorForMalformedIdentifier() throws Exception {
    mockMvc.perform(get("/api/coparent/families/not-an-object-id")
            .with(jwt().jwt(token -> token.subject("auth0|one")
                .claim("https://coparents.simonrowe.dev/email", "one@example.com"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code", is("invalid_request")));
  }
}
