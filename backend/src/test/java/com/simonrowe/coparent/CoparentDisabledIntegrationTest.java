package com.simonrowe.coparent;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** Proves the default-off switch isolates CoParent without removing the shared backend. */
class CoparentDisabledIntegrationTest extends AbstractIntegrationTest {

  @Test
  void featureIsUnavailableByDefault() throws Exception {
    mockMvc.perform(get("/api/coparent/me"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code", is("coparent_disabled")));
  }

  @Test
  void disablingCoparentDoesNotDisablePortfolioApi() throws Exception {
    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk());
  }
}
