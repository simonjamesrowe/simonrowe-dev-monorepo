package com.simonrowe.observability;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

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

  /**
   * Small bounded pool: score submission is best-effort telemetry, so a backlog should be
   * dropped rather than allowed to consume threads or memory.
   */
  @Bean("langfuseScoreExecutor")
  public Executor langfuseScoreExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("langfuse-score-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    executor.initialize();
    return executor;
  }

  @Bean
  public LangfuseScoreClient langfuseScoreClient(final RestClient.Builder restClientBuilder,
      final LangfuseProperties properties,
      @Qualifier("langfuseScoreExecutor") final Executor executor) {
    return new LangfuseScoreClient(restClientBuilder, properties, executor);
  }
}
