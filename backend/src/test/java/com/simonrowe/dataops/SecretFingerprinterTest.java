package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint is written by this application into {@code manifest.json} and
 * verified by {@code scripts/restore-platform.sh} in bash. The two must agree
 * byte for byte, so this test pins the scheme with fixed known-answer vectors
 * rather than only asserting self-consistency — a test that hashes with the same
 * code it is testing would pass happily while the bash side rejected every
 * legitimate restore.
 */
class SecretFingerprinterTest {

  /**
   * Generated out-of-band, deliberately not by this class:
   *
   * <pre>
   * printf '%s' 'platform-backup-fingerprint-v1:SALT:test-salt-value' | shasum -a 256
   * </pre>
   *
   * <p>If this assertion ever fails, the manifest format has changed and every
   * archive written under the old scheme becomes unverifiable. That is a
   * {@code schemaVersion} bump plus a matching change to the restore script, not
   * a test to update.
   */
  private static final String SALT_VECTOR =
      "83ddc930c4927052b7fdc193d5eb09dce5171d40038a3caba3e96918b51015b6";

  private static final String ENCRYPTION_KEY_VECTOR =
      "6c558bd825f1f2afa312a9ddb7b06bdd52d5cdc8f1cf687660213b1d5f901384";

  /**
   * What the digest would be if the input carried a trailing newline — i.e. what
   * a bash implementation using {@code echo} instead of {@code printf '%s'}
   * produces. Asserted to be different so the distinction is visible in the test
   * output rather than discovered during a restore under duress.
   */
  private static final String SALT_VECTOR_WITH_TRAILING_NEWLINE =
      "84971d1ec1c230dc52ae75662be269250c7153cad61fc9f4c50a4e493bf680fb";

  @Test
  void matchesTheKnownAnswerVector() {
    assertThat(SecretFingerprinter.fingerprint("SALT", "test-salt-value"))
        .isEqualTo(SALT_VECTOR);
  }

  @Test
  void doesNotHashTheTrailingNewline() {
    assertThat(SecretFingerprinter.fingerprint("SALT", "test-salt-value"))
        .isNotEqualTo(SALT_VECTOR_WITH_TRAILING_NEWLINE);
  }

  /**
   * Domain separation: the key name is part of the digest input, so swapping two
   * secrets between keys is detected rather than cancelling out.
   */
  @Test
  void bindsEachDigestToItsOwnKeyName() {
    String salt = SecretFingerprinter.fingerprint("SALT", "test-salt-value");
    String encryptionKey =
        SecretFingerprinter.fingerprint("ENCRYPTION_KEY", "test-salt-value");

    assertThat(encryptionKey).isEqualTo(ENCRYPTION_KEY_VECTOR);
    assertThat(encryptionKey).isNotEqualTo(salt);
  }

  @Test
  void producesLowercaseHexOfTheFullDigest() {
    String fingerprint = SecretFingerprinter.fingerprint("SALT", "anything");

    assertThat(fingerprint).hasSize(64).matches("[0-9a-f]{64}");
  }

  /**
   * An absent secret must be recorded as {@code null}, never as the digest of the
   * empty string. A digest of "" is a real, comparable value, so it would make an
   * unset secret look verified — the restore script must be able to tell
   * "unverifiable" from "matches".
   */
  @Test
  void returnsNullForAnAbsentOrBlankValue() {
    assertThat(SecretFingerprinter.fingerprint("SALT", null)).isNull();
    assertThat(SecretFingerprinter.fingerprint("SALT", "")).isNull();
    assertThat(SecretFingerprinter.fingerprint("SALT", "   ")).isNull();
  }

  @Test
  void fingerprintsExactlyTheFourSilentFailureKeys() {
    Map<String, String> fingerprints =
        SecretFingerprinter.fingerprintAll(name -> "value-of-" + name);

    assertThat(fingerprints)
        .containsOnlyKeys("ENCRYPTION_KEY", "SALT", "NEXTAUTH_SECRET",
            "DEPENDENCYTRACK_KEK");
  }

  /**
   * The map always carries all four keys, even when a value is absent. A missing
   * <em>key</em> would be ambiguous between "the secret was unset" and "this is
   * an older archive format".
   */
  @Test
  void alwaysCarriesAllFourKeysEvenWhenValuesAreAbsent() {
    Map<String, String> fingerprints = SecretFingerprinter.fingerprintAll(name -> null);

    assertThat(fingerprints)
        .containsOnlyKeys("ENCRYPTION_KEY", "SALT", "NEXTAUTH_SECRET",
            "DEPENDENCYTRACK_KEK");
    assertThat(fingerprints.values()).containsOnlyNulls();
  }

  @Test
  void neverExposesTheSecretValueItself() {
    Map<String, String> fingerprints =
        SecretFingerprinter.fingerprintAll(name -> "super-secret-" + name);

    assertThat(fingerprints.toString()).doesNotContain("super-secret");
  }
}
