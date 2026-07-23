package com.simonrowe;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.api.common.Ai;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApplicationTests {

  @MockitoBean
  private VectorStore vectorStore;

  @MockitoBean
  private EmbeddingModel embeddingModel;

  @MockitoBean
  private Ai ai;

  @Container
  static ConfluentKafkaContainer kafka =
      new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

  @Container
  static ElasticsearchContainer elasticsearch =
      new ElasticsearchContainer("elasticsearch:8.17.0")
          .withEnv("xpack.security.enabled", "false");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.elasticsearch.uris", elasticsearch::getHttpHostAddress);
  }

  @Autowired
  private KafkaHealthIndicator kafkaHealthIndicator;

  @Autowired
  private ObservationRegistry observationRegistry;

  @Autowired
  private Tracer tracer;

  @Test
  void contextLoads() {
  }

  /**
   * Spring AI's ChatClient/ChatModel emit gen_ai telemetry through the Micrometer Observation
   * API. Those observations only become OpenTelemetry spans (and therefore only reach Langfuse)
   * if a tracing ObservationHandler is registered — the job of micrometer-tracing-bridge-otel.
   * This asserts the bridge is wired: opening an observation must produce a live recording span.
   * Regression guard for the "Langfuse shows HTTP/Mongo traces but no chat generations" bug.
   */
  @Test
  void micrometerObservationsBecomeTracingSpans() {
    assertThat(observationRegistry.isNoop()).isFalse();

    Observation.createNotStarted("test.gen_ai", observationRegistry).observe(() -> {
      Span current = tracer.currentSpan();
      assertThat(current)
          .as("micrometer-tracing-bridge-otel must open an OTel span inside an observation")
          .isNotNull();
      assertThat(current.context().traceId()).isNotBlank();
    });
  }

  @Test
  void kafkaHealthReturnsUp() {
    Health health = kafkaHealthIndicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsKey("clusterId");
    assertThat(health.getDetails()).containsKey("brokerId");
  }
}
