package com.simonrowe.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Wires the Langfuse trace-enrichment beans. */
@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
public class ObservabilityConfig {

  /**
   * Lowest precedence so this runs after Spring AI's ToolCallingContentObservationFilter,
   * whose spring.ai.tool.call.* key values this filter remaps onto Langfuse names.
   */
  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  public LangfuseContentObservationFilter langfuseContentObservationFilter(
      final LangfuseProperties properties) {
    return new LangfuseContentObservationFilter(properties);
  }
}
