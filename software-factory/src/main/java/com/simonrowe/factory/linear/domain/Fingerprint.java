package com.simonrowe.factory.linear.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The dedup key: what makes two occurrences the same problem.
 *
 * <p>Computed from structured fields only, never from agent prose. {@code DeployActivities}
 * generates a {@code headline} that makes a good issue title and a terrible fingerprint — the same
 * failure phrased differently on two runs would file twice.
 */
public final class Fingerprint {

  /**
   * Version prefix, following the codebase's {@code FORMAT_VERSION} idiom.
   *
   * <p><strong>Bumping this orphans every existing ticket</strong>, so the next occurrence of a
   * known problem files a duplicate. It is a deliberate, documented one-time cost — not something
   * to change casually. Same warning as {@code NarrationScriptBuilder.FORMAT_VERSION}.
   */
  public static final String VERSION = "v1";

  private static final String SEPARATOR = "|";

  private Fingerprint() {
  }

  /**
   * Computes the fingerprint for a producer's key parts.
   *
   * @param producer the producer key, e.g. {@code deploy}
   * @param keyParts the structured parts identifying the problem, in a stable order
   * @return the lowercase hex SHA-256 digest
   * @throws IllegalArgumentException if the producer is blank or there are no key parts
   */
  public static String of(final String producer, final List<String> keyParts) {
    if (producer == null || producer.isBlank()) {
      throw new IllegalArgumentException("producer must not be blank");
    }
    if (keyParts == null || keyParts.isEmpty()) {
      throw new IllegalArgumentException(
          "at least one of the key parts is required, or every finding would share "
              + "one fingerprint");
    }
    String canonical = VERSION + ":" + producer + ":" + String.join(SEPARATOR, keyParts);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /**
   * Builds the attachment URL that carries a fingerprint onto a Linear issue.
   *
   * @param baseUrl the configured base, with or without a trailing slash
   * @param fingerprint the digest from {@link #of}
   * @return the synthetic, deliberately non-resolving key URL
   */
  public static String urlFor(final String baseUrl, final String fingerprint) {
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return trimmed + "/" + fingerprint;
  }
}
