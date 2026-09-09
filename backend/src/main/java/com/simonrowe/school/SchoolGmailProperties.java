package com.simonrowe.school;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for reading the school mailbox.
 *
 * <p>Separate from {@link SchoolProperties} so the credential can be reasoned about on its own:
 * this is the one piece of Term Time configuration that reads private correspondence, and keeping
 * it in its own type makes "which class holds the secret" answerable by looking at one file.
 *
 * <p>All three default empty. {@link #configured()} is the single check every caller uses, so an
 * incomplete credential behaves identically to no credential rather than failing halfway through
 * a sync with a partially-authenticated client.
 *
 * @param clientId OAuth client id
 * @param clientSecret OAuth client secret
 * @param refreshToken the long-lived refresh token minted by {@code scripts/termtime-gmail-auth.py}
 */
@ConfigurationProperties("school.gmail")
public record SchoolGmailProperties(
    String clientId,
    String clientSecret,
    String refreshToken
) {

  /** Normalises nulls so callers never have to distinguish absent from blank. */
  public SchoolGmailProperties {
    clientId = clientId == null ? "" : clientId.trim();
    clientSecret = clientSecret == null ? "" : clientSecret.trim();
    refreshToken = refreshToken == null ? "" : refreshToken.trim();
  }

  /**
   * Whether a complete credential is present.
   *
   * @return true when all three values are set
   */
  public boolean configured() {
    return !clientId.isEmpty() && !clientSecret.isEmpty() && !refreshToken.isEmpty();
  }
}
