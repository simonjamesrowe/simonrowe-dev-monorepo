package com.simonrowe.factory.ticketagent.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads a ticket identifier from what a person pasted: {@code SIM-123}, {@code sim-123}, or the
 * ticket's Linear URL.
 *
 * <p>A URL is accepted only on {@code linear.app}, compared by parsed host, so
 * {@code https://linear.app.example/issue/SIM-1} is refused rather than read as Linear.
 */
public final class TicketReference {

  private static final String LINEAR_HOST = "linear.app";

  private TicketReference() {
  }

  public static Optional<String> identifier(final String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    if (!trimmed.contains("/")) {
      String upper = trimmed.toUpperCase(Locale.ROOT);
      return TicketAgentService.isIdentifier(upper) ? Optional.of(upper) : Optional.empty();
    }
    try {
      URI uri = new URI(trimmed);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || !LINEAR_HOST.equalsIgnoreCase(uri.getHost())
          || uri.getPath() == null) {
        return Optional.empty();
      }
      String[] segments = uri.getPath().split("/");
      for (int index = 0; index < segments.length - 1; index++) {
        if ("issue".equals(segments[index])) {
          String upper = segments[index + 1].toUpperCase(Locale.ROOT);
          return TicketAgentService.isIdentifier(upper) ? Optional.of(upper) : Optional.empty();
        }
      }
      return Optional.empty();
    } catch (URISyntaxException exception) {
      return Optional.empty();
    }
  }
}
