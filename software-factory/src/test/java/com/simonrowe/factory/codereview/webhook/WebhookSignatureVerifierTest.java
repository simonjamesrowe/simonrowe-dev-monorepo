package com.simonrowe.factory.codereview.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

  private static final String SECRET = "secret";
  private static final byte[] BODY = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
  private static final String VALID =
      "sha256=d42142b53efbc7cf5cd20b6e074eb33707e0de3b368f698e6d6f6c824ffb8d37";

  private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();

  private static String sign(final byte[] body, final String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
  }

  @Test
  void acceptsSignatureProducedWithTheSharedSecret() {
    assertThat(verifier.isValid(BODY, VALID, SECRET)).isTrue();
  }

  @Test
  void acceptsEmptyBodyWhenTheSignatureMatchesIt() throws Exception {
    byte[] empty = new byte[0];

    assertThat(verifier.isValid(empty, sign(empty, SECRET), SECRET)).isTrue();
  }

  @Test
  void rejectsBodyAlteredAfterSigning() {
    byte[] tampered = "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8);

    assertThat(verifier.isValid(tampered, VALID, SECRET)).isFalse();
  }

  @Test
  void rejectsSignatureProducedWithDifferentSecret() throws Exception {
    assertThat(verifier.isValid(BODY, sign(BODY, "other-secret"), SECRET)).isFalse();
  }

  @Test
  void rejectsMissingSignatureHeader() {
    assertThat(verifier.isValid(BODY, null, SECRET)).isFalse();
  }

  @Test
  void rejectsSignatureWithoutTheSha256Prefix() {
    String hex = VALID.substring("sha256=".length());

    assertThat(verifier.isValid(BODY, hex, SECRET)).isFalse();
    assertThat(verifier.isValid(BODY, "sha1=" + hex, SECRET)).isFalse();
  }

  @Test
  void rejectsTruncatedSignature() {
    assertThat(verifier.isValid(BODY, VALID.substring(0, VALID.length() - 2), SECRET)).isFalse();
  }

  @Test
  void rejectsEmptySignatureValue() {
    assertThat(verifier.isValid(BODY, "", SECRET)).isFalse();
    assertThat(verifier.isValid(BODY, "sha256=", SECRET)).isFalse();
  }

  /**
   * An unset {@code GITHUB_WEBHOOK_SECRET} resolves to the empty string rather than null, so both
   * have to fail closed. Verifying against an empty secret would let anyone sign their own
   * payloads.
   */
  @Test
  void rejectsEveryRequestWhenNoSecretIsConfigured() {
    assertThat(verifier.isValid(BODY, VALID, null)).isFalse();
    assertThat(verifier.isValid(BODY, VALID, "")).isFalse();
    assertThat(verifier.isValid(BODY, VALID, "   ")).isFalse();
  }

  @Test
  void isCaseSensitiveAboutTheHexDigest() {
    assertThat(verifier.isValid(BODY, VALID.toUpperCase(Locale.ROOT), SECRET)).isFalse();
  }
}
