package com.simonrowe.agents;

import com.embabel.agent.openai.Gpt5ChatOptionsConverter;
import com.embabel.agent.openai.OpenAiCompatibleModelFactory;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.model.PricingModel;
import java.time.LocalDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.simonrowe.agents")
public class AgentConfig {

  /**
   * Registers {@code gpt-5.6-luna} with Embabel. Its bundled model registry
   * (@code classpath:models/openai-models.yml}) was verified against OpenAI on
   * 2026-03-29 and stops at the GPT-5.4 family, in 1.0.0 as well as 0.3.5, so
   * this model is invisible to {@code ai.withLlm(...)} without an explicit bean.
   *
   * <p>{@link Gpt5ChatOptionsConverter} is required rather than the standard
   * converter: gpt-5.6-luna accepts only the default temperature of 1 and
   * returns 400 for any other value.
   */
  @Bean
  public LlmService<?> gpt56LunaLlm(final OpenAiCompatibleModelFactory factory) {
    return factory.openAiCompatibleLlm(
        "gpt-5.6-luna",
        PricingModel.usdPer1MTokens(0.20, 1.20),
        "OpenAI",
        LocalDate.of(2026, 7, 30),
        Gpt5ChatOptionsConverter.INSTANCE);
  }
}
