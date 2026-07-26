package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class LangfusePropertiesTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(TestConfig.class);

  @Test
  void defaultsAreSafeWhenNothingIsConfigured() {
    runner.run(context -> {
      LangfuseProperties properties = context.getBean(LangfuseProperties.class);
      assertThat(properties.getHost()).isEqualTo("https://langfuse.simonrowe.dev");
      assertThat(properties.getEnvironment()).isEqualTo("development");
      assertThat(properties.isScoresEnabled()).isFalse();
      assertThat(properties.isContentCaptureEnabled()).isTrue();
    });
  }

  @Test
  void propertiesBindFromConfiguration() {
    runner.withPropertyValues(
        "langfuse.host=http://localhost:3000",
        "langfuse.public-key=pk-test",
        "langfuse.secret-key=sk-test",
        "langfuse.environment=production",
        "langfuse.scores-enabled=true",
        "langfuse.content-capture-enabled=false"
    ).run(context -> {
      LangfuseProperties properties = context.getBean(LangfuseProperties.class);
      assertThat(properties.getHost()).isEqualTo("http://localhost:3000");
      assertThat(properties.getPublicKey()).isEqualTo("pk-test");
      assertThat(properties.getSecretKey()).isEqualTo("sk-test");
      assertThat(properties.getEnvironment()).isEqualTo("production");
      assertThat(properties.isScoresEnabled()).isTrue();
      assertThat(properties.isContentCaptureEnabled()).isFalse();
    });
  }

  @Configuration
  @EnableConfigurationProperties(LangfuseProperties.class)
  static class TestConfig {
  }
}
