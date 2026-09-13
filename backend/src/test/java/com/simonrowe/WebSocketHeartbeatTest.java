package com.simonrowe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;

/**
 * The STOMP connection has to write something, periodically, or every proxy between the browser
 * and this process treats it as dead and closes it — nginx at 60s, Cloudflare at around 100s,
 * the pinggy tunnel on its own schedule. The symptom is not an error anywhere: the socket is
 * simply gone the next time the visitor types, and Term Time (a full page that sits open while
 * somebody reads) says it cannot reach Term Time.
 *
 * <p>Spring's simple broker disables heartbeats by default — {@code "0, 0"} unless a
 * {@code TaskScheduler} is set — and it advertises that zero in its CONNECTED frame, which
 * switches the client's own heartbeat off however the client is configured. This test pins that
 * it no longer does, because nothing else would notice: a context with heartbeats off starts
 * perfectly, serves every request, and passes every other test in this suite.
 */
class WebSocketHeartbeatTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      // WebSocketConfig is meta-annotated @EnableWebSocketMessageBroker, so registering it
      // brings the whole broker configuration with it. Deliberately not the full application
      // context: that would need Mongo, Elasticsearch and Kafka to answer a question about
      // two numbers.
      .withUserConfiguration(WebSocketConfig.class, PlaceholderConfig.class);

  @Test
  void theSimpleBrokerSendsHeartbeats() {
    contextRunner.run(context -> {
      SimpleBrokerMessageHandler broker = context.getBean(SimpleBrokerMessageHandler.class);

      // First value: how often the server writes. Second: how often it expects the client to.
      assertThat(broker.getHeartbeatValue()).containsExactly(10_000L, 10_000L);

      // Without a scheduler the values above are accepted and then never acted on — the
      // handler only starts its heartbeat task when one is present.
      assertThat(broker.getTaskScheduler()).isNotNull();
    });
  }

  /** Resolves the {@code ${cors.allowed-origins:}} default; nothing registers one by default. */
  @Configuration(proxyBeanMethods = false)
  static class PlaceholderConfig {
    @Bean
    static PropertySourcesPlaceholderConfigurer placeholders() {
      return new PropertySourcesPlaceholderConfigurer();
    }
  }
}
