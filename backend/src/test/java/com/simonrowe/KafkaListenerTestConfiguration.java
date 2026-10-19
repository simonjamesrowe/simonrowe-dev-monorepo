package com.simonrowe;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Preserves the test profile's disabled consumers when cached contexts restart. */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaListenerTestConfiguration {

  @Bean
  static BeanPostProcessor preserveKafkaListenerAutoStartup() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(final Object bean, final String beanName) {
        if (bean instanceof KafkaListenerEndpointRegistry registry) {
          // Spring 7 pauses/restarts cached test contexts. The registry otherwise starts
          // every listener after refresh, ignoring auto-startup=false and consuming work
          // against another test's fixtures in the shared Mongo database.
          registry.setAlwaysStartAfterRefresh(false);
        }
        return bean;
      }
    };
  }
}
