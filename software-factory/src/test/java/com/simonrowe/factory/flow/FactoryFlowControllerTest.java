package com.simonrowe.factory.flow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code /api/factory/flow} is deliberately unauthenticated, on the same terms as {@code
 * /api/factory/status}: node keys, labels, integer counts and diagnostic strings, none of which
 * is a credential or free text naming a specific run. The per-node detail endpoint carries the
 * titles and identifiers, and is token-protected there instead.
 *
 * <p>{@link #servesTheFlowWithNoTokenAtAll()} pins the openness deliberately, so a future reader
 * cannot mistake the absence of a token check for an oversight.
 */
class FactoryFlowControllerTest {

  private static final String PATH = "/api/factory/flow";

  private final FactoryFlowService service = mock(FactoryFlowService.class);

  @Test
  void servesTheFlowWithNoTokenAtAll() throws Exception {
    FactoryFlowResponse expected = new FactoryFlowResponse(Instant.now(), List.of(), List.of());
    when(service.flow()).thenReturn(expected);

    mvc().perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodes").isArray())
        .andExpect(jsonPath("$.edges").isArray());
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new FactoryFlowController(service)).build();
  }
}
