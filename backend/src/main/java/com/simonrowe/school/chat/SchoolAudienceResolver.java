package com.simonrowe.school.chat;

import com.simonrowe.school.retrieval.SchoolAudience;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Resolves the audience for a STOMP turn from a bearer token in the message body.
 *
 * <p>The HTTP path gets this for free from Spring Security. A STOMP SEND frame does not, so the
 * token travels in the body — and this class is what stops that being a hole. The token is
 * <b>decoded and validated</b> by the application's real {@link JwtDecoder} (signature, issuer,
 * expiry), exactly as the filter chain would. Anything that fails validation resolves to
 * anonymous rather than throwing, because a bad token is a request for the public tier, not an
 * error worth failing a chat turn over.
 *
 * <p>The roles claim is read the same way {@code RolesJwtAuthenticationConverter} reads it, with
 * the same {@code ROLE_} prefix. If that converter ever changes shape, this must follow — which
 * is why the claim name is restated here rather than silently duplicated.
 */
@Component
public class SchoolAudienceResolver {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolAudienceResolver.class);

  /** Mirrors {@code RolesJwtAuthenticationConverter.ROLES_CLAIM}. */
  private static final String ROLES_CLAIM = "https://simonrowe.dev/roles";

  /** Mirrors {@code SchoolChatController.SCHOOL_AUTHORITY}, without the ROLE_ prefix. */
  private static final String GRANTING_ROLE = "DEV_PORTAL_ADMIN";

  private final JwtDecoder jwtDecoder;

  public SchoolAudienceResolver(final JwtDecoder jwtDecoder) {
    this.jwtDecoder = jwtDecoder;
  }

  /**
   * Resolves the audience for a token.
   *
   * @param accessToken a bearer token, or null/blank for anonymous
   * @return the audience, defaulting to anonymous on anything unexpected
   */
  public SchoolAudience resolve(final String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      return SchoolAudience.anonymous();
    }
    try {
      final Jwt jwt = jwtDecoder.decode(accessToken);
      final List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
      final boolean granted = roles != null && roles.contains(GRANTING_ROLE);
      return granted ? SchoolAudience.schoolMember() : SchoolAudience.anonymous();
    } catch (RuntimeException e) {
      LOG.debug("Rejecting a school chat token, falling back to anonymous: {}", e.getMessage());
      return SchoolAudience.anonymous();
    }
  }
}
