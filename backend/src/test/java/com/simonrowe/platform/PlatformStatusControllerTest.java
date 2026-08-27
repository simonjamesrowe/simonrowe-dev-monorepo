package com.simonrowe.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class PlatformStatusControllerTest extends AbstractIntegrationTest {

  @Test
  void isPublicAndNeedsNoAuthentication() throws Exception {
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk());
  }

  @Test
  void reportsTheBackendFirstAndAlwaysReachable() throws Exception {
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.services[0].name").value("backend"))
        .andExpect(jsonPath("$.services[0].reachable").value(true))
        .andExpect(jsonPath("$.services[0].commit").exists())
        .andExpect(jsonPath("$.services[0].shortCommit").exists());
  }

  @Test
  void reportsTheSiblingServicesEvenWhenUnreachable() throws Exception {
    // Nothing is listening on software-factory:8090 in a test, so both must come back
    // as not reporting rather than being omitted or failing the request.
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.services[1].name").value("software-factory"))
        .andExpect(jsonPath("$.services[1].reachable").value(false))
        .andExpect(jsonPath("$.services[2].name").value("deployer"))
        .andExpect(jsonPath("$.services[2].reachable").value(false));
  }

  @Test
  void listsThirdPartyComponentsWithoutFirstPartyOnes() throws Exception {
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components[?(@.name == 'mongodb')].tag").value("8"))
        .andExpect(jsonPath("$.components[?(@.name == 'backend')]").isEmpty())
        .andExpect(jsonPath("$.components[?(@.name == 'alloy')].floating").value(true));
  }
}
