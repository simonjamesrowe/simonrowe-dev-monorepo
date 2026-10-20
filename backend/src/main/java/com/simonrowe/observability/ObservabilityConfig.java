package com.simonrowe.observability;

import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/** Wires the Langfuse trace-enrichment beans. */
@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
public class ObservabilityConfig {

  @Bean
  static RepositoryTracing repositoryTracing(
      final ObjectProvider<ObservationRegistry> registries) {
    // Resolve the registry on invocation, not while post-processors are being registered.
    return new RepositoryTracing(registries);
  }

  /**
   * Deliberately unordered: the filter reads content from the observation context's own
   * accessors, so it is independent of where it lands relative to Spring AI's own filters.
   */
  @Bean
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
    executor.setTaskDecorator(
        new org.springframework.core.task.support.ContextPropagatingTaskDecorator());
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
