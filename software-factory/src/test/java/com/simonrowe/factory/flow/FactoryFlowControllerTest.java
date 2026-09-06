package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

  @Test
  void flowNodePayloadShapeCarriesNoTitledOrFreeTextContent() {
    // This class's Javadoc justifies staying unauthenticated by what the response CONTAINS: node
    // keys and labels from a fixed topology, integer counts, diagnostic strings - never a ticket
    // subject, pull request title or other free text naming a specific run. Nothing else enforces
    // that: adding a `lastFailureTitle` or `recentItems` component to FlowNode would serve that
    // content unauthenticated with every other test still green. Pin the component set exactly,
    // so a new component fails loudly here and is redirected to the token-protected
    // /{nodeKey} detail endpoint instead.
    Set<String> expected = Set.of("key", "kind", "band", "label", "counts", "health", "diagnostic");

    Set<String> actual = Arrays.stream(FlowNode.class.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());

    assertThat(actual)
        .as("FlowNode's record components changed from %s to %s. /api/factory/flow is "
            + "deliberately unauthenticated because its payload is limited to node keys, labels, "
            + "integer counts and diagnostic strings - titled or free-text content (a ticket "
            + "subject, a pull request title, a list of recent runs) belongs on the "
            + "token-protected GET /api/factory/flow/{nodeKey} detail endpoint instead, never here",
            expected, actual)
        .isEqualTo(expected);
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new FactoryFlowController(service)).build();
  }
}
