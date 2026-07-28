package com.simonrowe.reviewer.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

  private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();

  @Test
  void acceptsValidGitHubSignatureAndRejectsTampering() {
    byte[] body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
    String signature =
        "sha256=d42142b53efbc7cf5cd20b6e074eb33707e0de3b368f698e6d6f6c824ffb8d37";

    assertThat(verifier.isValid(body, signature, "secret")).isTrue();
    assertThat(
            verifier.isValid(
                "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8),
                signature,
                "secret"))
        .isFalse();
    assertThat(verifier.isValid(body, null, "secret")).isFalse();
  }
}
