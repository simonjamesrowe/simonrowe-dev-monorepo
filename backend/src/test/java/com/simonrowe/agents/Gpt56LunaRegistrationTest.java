package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.spi.LlmService;
import com.simonrowe.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guards the explicit registration of gpt-5.6-luna. Embabel's bundled model
 * registry stops at GPT-5.4, so if a future upgrade changes the registration
 * API this fails the build rather than failing at 08:00 on a Monday.
 */
@SpringBootTest
class Gpt56LunaRegistrationTest extends AbstractIntegrationTest {

  @Autowired
  private Map<String, LlmService<?>> llmServices;

  @Test
  void gpt56LunaIsRegistered() {
    assertThat(llmServices.values())
        .anySatisfy(llm -> assertThat(llm.getName()).isEqualTo("gpt-5.6-luna"));
  }
}
