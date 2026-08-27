package com.simonrowe.factory.linear.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinearPropertiesTest {

  private static LinearProperties defaults() {
    return new LinearProperties(false, null, null, null, null, false, null, null);
  }

  @Test
  void isDisabledAndCredentiallessByDefault() {
    LinearProperties properties = defaults();
    assertThat(properties.enabled()).isFalse();
    assertThat(properties.apiKey()).isEmpty();
    assertThat(properties.dryRun()).isFalse();
  }

  @Test
  void appliesTheDocumentedEndpointAndFingerprintDefaults() {
    LinearProperties properties = defaults();
    assertThat(properties.apiBaseUrl()).isEqualTo("https://api.linear.app/graphql");
    assertThat(properties.fingerprintBaseUrl())
        .isEqualTo("https://factory.simonrowe.dev/fingerprint");
    // 15s x the four sequential calls one cold filing makes = 60s, inside the 90s
    // startToCloseTimeout both producers give fileIssue. See LinearProperties.
    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void carriesPolicyForEachKnownProducer() {
    LinearProperties properties = defaults();
    assertThat(properties.producerFor("deploy").label()).isEqualTo("factory:deploy");
    assertThat(properties.producerFor("deploy").priority()).isEqualTo(1);
    assertThat(properties.producerFor("cvefix").label()).isEqualTo("factory:cvefix");
    assertThat(properties.producerFor("cvefix").priority()).isEqualTo(3);
  }

  @Test
  void anUnconfiguredProducerFallsBackToGenericLabelRatherThanFailing() {
    // A future producer that ships before its config entry must still file, not throw:
    // losing the finding is worse than mislabelling it.
    LinearProperties.Producer fallback = defaults().producerFor("bughunter");
    assertThat(fallback.label()).isEqualTo("factory:bughunter");
    assertThat(fallback.priority()).isEqualTo(3);
  }

  @Test
  void configuredProducersOverrideTheDefaults() {
    LinearProperties properties =
        new LinearProperties(
            true, "k", null, "SIM", null, false, null,
            Map.of("deploy", new LinearProperties.Producer("urgent:deploy", 2)));
    assertThat(properties.producerFor("deploy").label()).isEqualTo("urgent:deploy");
    assertThat(properties.producerFor("deploy").priority()).isEqualTo(2);
  }
}
