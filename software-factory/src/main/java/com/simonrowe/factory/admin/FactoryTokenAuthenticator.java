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
    check(properties.api().triggerToken(), suppliedToken);
  }

  /**
   * Authenticates with the separate, read-only token rather than the trigger token.
   *
   * <p>This exists so a container may be granted read access to titled, per-run detail without
   * also gaining the trigger token that starts a deploy, a code review or a platform backup. The
   * {@code deployer} holds {@code FACTORY_READ_TOKEN} but deliberately never
   * {@code FACTORY_TRIGGER_TOKEN} — see {@code docker-compose.prod.yml}'s comment on that service
   * for why. Same fail-closed-when-unconfigured behaviour and constant-time comparison as {@link
   * #authenticate(String)}, against a different configured value.
   *
   * @param suppliedToken the value of the {@code X-Factory-Token} header, or null
   */
  public void authenticateRead(final String suppliedToken) {
    check(properties.api().readToken(), suppliedToken);
  }

  private void check(final String configured, final String suppliedToken) {
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
