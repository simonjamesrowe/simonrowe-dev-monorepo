package com.simonrowe.reviewer.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Verifies GitHub's SHA-256 HMAC over the exact request bytes. */
@Component
public class WebhookSignatureVerifier {

  public boolean isValid(final byte[] body, final String signature, final String secret) {
    if (signature == null
        || !signature.startsWith("sha256=")
        || secret == null
        || secret.isBlank()) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          signature.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HmacSHA256 is unavailable", exception);
    }
  }
}
