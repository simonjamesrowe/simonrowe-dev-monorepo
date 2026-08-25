package com.simonrowe.dataops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.lang.Nullable;

/**
 * One-way fingerprints of the host secrets a platform archive was captured under.
 *
 * <p>Why this exists: a restored database is worthless without the {@code .env} it
 * was encrypted under. Langfuse encrypts stored LLM API keys with
 * {@code ENCRYPTION_KEY} and hashes API keys with {@code SALT}; Dependency-Track
 * encrypts its secrets with {@code DEPENDENCYTRACK_KEK}. Restoring onto a host
 * with a freshly generated {@code .env} produces rows that load without error and
 * then fail to decrypt — a failure that presents as success. Recording a
 * fingerprint lets {@code scripts/restore-platform.sh} refuse before doing the
 * damage.
 *
 * <p>The secret values themselves are never written anywhere. The {@code .env}
 * file is deliberately not included in the archive either: it is a secret, Drive
 * is not an appropriate place for it, and it is already reproduced from
 * {@code ~/workspace/simonjamesrowe/env}.
 *
 * <p><strong>The digest scheme is a cross-language contract.</strong> The bash
 * side must compute the identical value, which means two things matter more than
 * they look:
 *
 * <ul>
 *   <li><em>No trailing newline.</em> {@code echo "$v" | sha256sum} hashes
 *       {@code v\n} and would never match; the script must use
 *       {@code printf '%s'}. This is the most likely way to ship a fingerprint
 *       check that refuses every legitimate restore.
 *   <li><em>The domain-separation prefix.</em> A bare {@code SHA-256(value)} is a
 *       rainbow-table-able hash of a live production secret sitting in cloud
 *       storage. Prefixing with a fixed non-secret label and the key's own name
 *       makes precomputed tables useless and pins each digest to its key, so
 *       swapping {@code SALT} and {@code ENCRYPTION_KEY} is detected rather than
 *       cancelling out.
 * </ul>
 *
 * <p>{@code SecretFingerprinterTest} pins the scheme with fixed known-answer
 * vectors generated outside this class.
 */
public final class SecretFingerprinter {

  /** Versioned alongside the manifest's {@code schemaVersion}. */
  static final String DOMAIN_PREFIX = "platform-backup-fingerprint-v1:";

  /**
   * The secrets whose mismatch corrupts data <em>silently</em>.
   *
   * <p>Database passwords are deliberately absent: a password mismatch fails
   * loudly at connect time, so it needs no detection mechanism. Only silent
   * failures justify the check.
   */
  static final List<String> FINGERPRINTED_KEYS = List.of(
      "ENCRYPTION_KEY",
      "SALT",
      "NEXTAUTH_SECRET",
      "DEPENDENCYTRACK_KEK"
  );

  private SecretFingerprinter() {
  }

  /**
   * Fingerprints a single secret.
   *
   * @param name  the environment variable name, which is part of the digest input
   * @param value the secret value; may be null or blank
   * @return lowercase hex of the full SHA-256 digest, or {@code null} when the
   *     value is absent or blank. Never the digest of the empty string — that is
   *     a real, comparable value and would make an unset secret look verified,
   *     when the restore script needs to distinguish "unverifiable" from
   *     "matches".
   */
  @Nullable
  public static String fingerprint(final String name, @Nullable final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    byte[] digest = sha256(DOMAIN_PREFIX + name + ":" + value);
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16));
      hex.append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }

  /**
   * Fingerprints every key in {@link #FINGERPRINTED_KEYS}, reading values from the
   * process environment.
   *
   * <p>The backend declares {@code env_file: .env} in {@code
   * docker-compose.prod.yml}, so all four values are already in its environment —
   * the same file Langfuse and Dependency-Track receive them from. No new secrets
   * plumbing is needed.
   *
   * @return an ordered map with all four keys always present, values null where
   *     the secret is absent
   */
  public static Map<String, String> fingerprintAll() {
    return fingerprintAll(System::getenv);
  }

  /**
   * Fingerprints every key using the supplied value lookup. Exists so tests can
   * supply values without mutating the process environment.
   *
   * @param valueLookup resolves an environment variable name to its value, or
   *     null when unset
   * @return an ordered map with all four keys always present
   */
  public static Map<String, String> fingerprintAll(
      final UnaryOperator<String> valueLookup) {
    Map<String, String> fingerprints = new LinkedHashMap<>();
    for (String key : FINGERPRINTED_KEYS) {
      fingerprints.put(key, fingerprint(key, valueLookup.apply(key)));
    }
    return Collections.unmodifiableMap(fingerprints);
  }

  private static byte[] sha256(final String input) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is mandated by the JDK spec; unreachable on any real JVM.
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
