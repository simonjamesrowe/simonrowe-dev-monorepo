package com.simonrowe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Ensures integration tests cannot consume queued work from earlier test methods. */
class KafkaListenerIsolationTest extends AbstractIntegrationTest {

  @Autowired
  private KafkaListenerEndpointRegistry registry;

  @Autowired
  private ConfigurableApplicationContext context;

  @Test
  void testProfileKeepsEveryKafkaListenerStopped() {
    assertListenersStopped();
    // Spring's cached test contexts pause and restart between test classes.
    context.pause();
    context.restart();
    assertListenersStopped();
  }

  private void assertListenersStopped() {
    assertThat(registry.getListenerContainers()).isNotEmpty().allSatisfy(container -> {
      assertThat(container.isAutoStartup()).as(container.getListenerId()).isFalse();
      assertThat(container.isRunning()).as(container.getListenerId()).isFalse();
    });
  }
}
