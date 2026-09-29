package com.simonrowe.common;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where an editable link may point: a path on this site, or an absolute {@code https} URL.
 *
 * <p>Decided by parsing, never by prefix. {@code //evil.example} starts with {@code /} and
 * is a protocol-relative link off the site; {@code https://simonrowe.dev.evil.example}
 * starts with the site's origin. A backslash is refused outright, because browsers read
 * {@code /\evil.example} as {@code //evil.example}. No regular expression is involved, so
 * input length cannot make the check expensive.
 */
public final class LinkTargets {

  public static final int MAX_LENGTH = 2048;

  private LinkTargets() {
  }

  /** An absolute {@code https} URL only — for links that must leave the site. */
  public static boolean isHttpsUrl(final String href) {
    return isAllowed(href) && !href.startsWith("/");
  }

  public static boolean isAllowed(final String href) {
    if (href == null || href.isEmpty() || href.length() > MAX_LENGTH) {
      return false;
    }
    for (int i = 0; i < href.length(); i++) {
      char c = href.charAt(i);
      if (c <= ' ' || c == '\\' || c == 0x7f) {
        return false;
      }
    }
    final URI uri;
    try {
      uri = new URI(href);
    } catch (URISyntaxException e) {
      return false;
    }
    if (href.startsWith("/")) {
      return !href.startsWith("//") && uri.getScheme() == null && uri.getRawAuthority() == null;
    }
    return "https".equalsIgnoreCase(uri.getScheme())
        && uri.getHost() != null
        && !uri.getHost().isEmpty()
        && uri.getRawUserInfo() == null;
  }
}
