package com.simonrowe.factory.admin;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Authenticates internal factory actions with the shared trigger token. */
@Component
public class FactoryTokenAuthenticator {

  private final CodeReviewProperties properties;

  public FactoryTokenAuthenticator(final CodeReviewProperties properties) {
    this.properties = properties;
  }

  /**
   * Fails closed when the token is unconfigured and compares configured tokens in constant time.
   */
  public void authenticate(final String suppliedToken) {
    String configured = properties.api().triggerToken();
    if (configured == null || configured.isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Manual factory actions are disabled");
    }
    byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
    byte[] supplied = suppliedToken == null
        ? new byte[0]
        : suppliedToken.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, supplied)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }
}
