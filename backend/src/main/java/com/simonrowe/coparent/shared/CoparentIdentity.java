package com.simonrowe.coparent.shared;

import com.simonrowe.coparent.config.CoparentProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Derives all CoParent identity data from the verified resource-server JWT. */
@Component
public class CoparentIdentity {

  private final CoparentProperties properties;

  public CoparentIdentity(final CoparentProperties properties) {
    this.properties = properties;
  }

  /** Returns the immutable Auth0 subject of the current request. */
  public String subject() {
    return jwt().getSubject();
  }

  /** Returns the configured verified-email claim, normalised for comparisons. */
  public String email() {
    final String email = jwt().getClaimAsString(properties.auth0EmailClaim());
    if (email == null || email.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified email is required");
    }
    return email.trim().toLowerCase();
  }

  private Jwt jwt() {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
    }
    return jwt;
  }
}
