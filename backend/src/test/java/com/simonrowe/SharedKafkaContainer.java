package com.simonrowe;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * One Kafka broker per test JVM, started on first use and never stopped — the same
 * singleton pattern as {@link SharedMongoContainer}, and deliberately not the
 * {@code @Container} lifecycle, which starts and stops a broker per test class.
 *
 * <p>Integration tests need a real broker rather than a mocked publisher because
 * {@code KafkaTemplate.send()} is only asynchronous once the producer holds topic
 * metadata. Pointed at a dead {@code localhost:9092} it blocks the calling thread for
 * {@code max.block.ms} on every publish, which is a property of the client that no
 * amount of mocking downstream of it would exercise.
 */
public final class SharedKafkaContainer {

  static final ConfluentKafkaContainer KAFKA =
      new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

  static {
    KAFKA.start();
  }

  private SharedKafkaContainer() {
  }

  public static void configureProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }
}
