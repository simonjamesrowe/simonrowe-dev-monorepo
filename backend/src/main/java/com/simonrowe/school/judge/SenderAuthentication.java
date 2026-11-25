package com.simonrowe.school.judge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether Google verified that an email really came from the school.
 *
 * <p>The sender allowlist matches the {@code From} address, which anyone can write. That is fine
 * for deciding what to read, and not enough for publishing: a forged email "from" the school
 * could otherwise publish itself. So the judge publishes only mail Google authenticated.
 *
 * <p><b>The school's broadcasts fail DMARC.</b> Measured on 2026-11-24 across every ingested
 * email: 129 of the 136 come from {@code info@} through SendGrid (Arbor's mailer), signed by
 * {@code sendgrid.net} only, with {@code dmarc=fail} under the school's {@code p=NONE} policy.
 * A "signed by the school's domain" rule would publish none of them. What Google does verify is
 * SPF for the envelope sender, and SendGrid sets that envelope to
 * {@code bounces+<account>-...@sendgrid.net}, where the account number is the sending account's
 * and not something another SendGrid customer can choose. So a broadcast counts as
 * authenticated when SPF passed for an envelope matching a configured glob for the school's
 * account. Mail sent directly by the school's own server passes DMARC, which also counts.
 *
 * <p>Only the first {@code Authentication-Results} header that Google's MX stamped is read. A
 * sender can add any number of its own below it, claiming whatever it likes.
 */
public final class SenderAuthentication {

  private static final String GOOGLE_MX = "mx.google.com";
  // Possessive throughout: the header is untrusted input of unbounded length.
  private static final Pattern MAIL_FROM =
      Pattern.compile("smtp\\.mailfrom=\"?+([^\\s\";]++)");
  private static final Pattern HEADER_FROM =
      Pattern.compile("header\\.from=([^\\s;]++)");

  private SenderAuthentication() {
  }

  /**
   * Picks Google's own header out of a message's {@code Authentication-Results} headers.
   *
   * @param headers every {@code Authentication-Results} value, in the order Gmail returned them
   * @return the first one Google's MX stamped, if any
   */
  public static Optional<String> googleHeader(final List<String> headers) {
    return headers.stream()
        .filter(value -> value != null && value.trim().startsWith(GOOGLE_MX))
        .findFirst();
  }

  /**
   * Whether the header proves the message came from the school.
   *
   * @param authResults Google's {@code Authentication-Results} header, or null
   * @param schoolDomain the domain a DMARC pass must be for
   * @param trustedEnvelopes {@code prefix*suffix} globs for envelope senders SPF must pass for
   * @return true when DMARC passed for the school's domain, or SPF passed for a trusted envelope
   */
  public static boolean authenticated(
      final String authResults, final String schoolDomain, final List<String> trustedEnvelopes) {
    if (authResults == null) {
      return false;
    }
    boolean spfPass = false;
    String mailFrom = null;
    for (String clause : authResults.split(";")) {
      final String trimmed = clause.trim().toLowerCase(Locale.ROOT);
      if (trimmed.startsWith("dmarc=pass") && !schoolDomain.isEmpty()) {
        final Matcher from = HEADER_FROM.matcher(trimmed);
        if (from.find() && from.group(1).equals(schoolDomain)) {
          return true;
        }
      }
      if (trimmed.startsWith("spf=pass")) {
        spfPass = true;
        final Matcher envelope = MAIL_FROM.matcher(trimmed);
        if (envelope.find()) {
          mailFrom = envelope.group(1);
        }
      }
    }
    if (!spfPass || mailFrom == null) {
      return false;
    }
    final String envelope = mailFrom;
    return trustedEnvelopes.stream().anyMatch(glob -> matchesGlob(envelope, glob));
  }

  /**
   * One {@code *} glob, matched by string operations rather than a regex built from config.
   *
   * @param value the envelope sender
   * @param glob {@code prefix*suffix}, or an exact address
   * @return whether it matches
   */
  static boolean matchesGlob(final String value, final String glob) {
    final int star = glob.indexOf('*');
    if (star < 0) {
      return value.equals(glob);
    }
    final String prefix = glob.substring(0, star);
    final String suffix = glob.substring(star + 1);
    return value.length() >= prefix.length() + suffix.length()
        && value.startsWith(prefix)
        && value.endsWith(suffix);
  }
}
