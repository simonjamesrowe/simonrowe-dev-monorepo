package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NarrationPropertiesTest {

  private static NarrationProperties properties(
      final String projectNumber, final String apiKey, final String bucket) {
    return new NarrationProperties(true, "project", projectNumber, apiKey, "global",
        "voice", "en-GB", bucket, 50_000, 1_000_000, Duration.ofMillis(1),
        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
  }

  @Nested
  @DisplayName("usesApiKey")
  class UsesApiKey {

    @Test
    void isFalseWhenUnset() {
      assertThat(properties("123456789012", null, "bucket").usesApiKey()).isFalse();
    }

    @Test
    void isFalseWhenBlank() {
      assertThat(properties("123456789012", "   ", "bucket").usesApiKey()).isFalse();
    }

    @Test
    void isTrueWhenSet() {
      assertThat(properties("123456789012", "key", "bucket").usesApiKey()).isTrue();
    }
  }

  @Nested
  @DisplayName("isProviderConfigured")
  class IsProviderConfigured {

    @Test
    @DisplayName("an API key deployment needs no project number or bucket")
    void apiKeyWithoutLongAudioSettings() {
      assertThat(properties(null, "key", null).isProviderConfigured()).isTrue();
    }

    @Test
    @DisplayName("ADC still requires the project number and bucket the Long Audio "
        + "route resolves its resource path and output URI from")
    void adcRequiresLongAudioSettings() {
      assertThat(properties(null, null, "bucket").isProviderConfigured()).isFalse();
      assertThat(properties("123456789012", null, null).isProviderConfigured()).isFalse();
      assertThat(properties("123456789012", null, "bucket").isProviderConfigured())
          .isTrue();
    }

    @Test
    @DisplayName("disabled overrides any credential")
    void disabledIsNeverConfigured() {
      NarrationProperties disabled = new NarrationProperties(
          false, "project", "123456789012", "key", "global", "voice", "en-GB",
          "bucket", 50_000, 1_000_000, Duration.ofMillis(1), Duration.ofSeconds(1),
          Duration.ofSeconds(1), Duration.ofSeconds(1));
      assertThat(disabled.isProviderConfigured()).isFalse();
    }
  }
}
