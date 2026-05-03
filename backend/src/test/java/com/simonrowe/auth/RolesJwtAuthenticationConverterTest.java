package com.simonrowe.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class RolesJwtAuthenticationConverterTest {

  private final RolesJwtAuthenticationConverter converter =
      new RolesJwtAuthenticationConverter();

  @Test
  void mapsRolesClaimToRoleAuthorities() {
    final Jwt jwt = jwt(Map.of(
        "sub", "user-1",
        RolesJwtAuthenticationConverter.ROLES_CLAIM,
        List.of("DEV_PORTAL_ADMIN", "EDITOR")
    ));

    final AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token.getAuthorities())
        .extracting("authority")
        .containsExactlyInAnyOrder("ROLE_DEV_PORTAL_ADMIN", "ROLE_EDITOR");
  }

  @Test
  void emitsNoAuthoritiesWhenRolesClaimAbsent() {
    final Jwt jwt = jwt(Map.of("sub", "user-1"));

    final AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token.getAuthorities()).isEmpty();
  }

  @Test
  void emitsNoAuthoritiesWhenRolesClaimEmpty() {
    final Jwt jwt = jwt(Map.of(
        "sub", "user-1",
        RolesJwtAuthenticationConverter.ROLES_CLAIM,
        List.of()
    ));

    final AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token.getAuthorities()).isEmpty();
  }

  @Test
  void filtersOutBlankRoleEntries() {
    final Jwt jwt = jwt(Map.of(
        "sub", "user-1",
        RolesJwtAuthenticationConverter.ROLES_CLAIM,
        List.of("", "  ", "DEV_PORTAL_ADMIN")
    ));

    final AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token.getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_DEV_PORTAL_ADMIN");
  }

  @Test
  void includesScopeAuthoritiesAlongsideRoles() {
    final Jwt jwt = jwt(Map.of(
        "sub", "user-1",
        "scope", "read:blogs write:blogs",
        RolesJwtAuthenticationConverter.ROLES_CLAIM,
        List.of("DEV_PORTAL_ADMIN")
    ));

    final AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token.getAuthorities())
        .extracting("authority")
        .contains("ROLE_DEV_PORTAL_ADMIN", "SCOPE_read:blogs", "SCOPE_write:blogs");
  }

  private static Jwt jwt(final Map<String, Object> claims) {
    return new Jwt(
        "token-value",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Map.of("alg", "RS256"),
        claims
    );
  }
}
