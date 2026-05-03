package com.simonrowe.auth;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

@Component
public class RolesJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  static final String ROLES_CLAIM = "https://simonrowe.dev/roles";

  private final JwtGrantedAuthoritiesConverter scopesConverter =
      new JwtGrantedAuthoritiesConverter();

  @Override
  public AbstractAuthenticationToken convert(final Jwt jwt) {
    final Collection<GrantedAuthority> scopeAuthorities =
        scopesConverter.convert(jwt);
    final List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
    final Stream<GrantedAuthority> roleAuthorities = roles == null
        ? Stream.empty()
        : roles.stream()
            .filter(role -> role != null && !role.isBlank())
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role));
    final List<GrantedAuthority> all = Stream.concat(
        scopeAuthorities == null ? Stream.empty() : scopeAuthorities.stream(),
        roleAuthorities
    ).toList();
    return new JwtAuthenticationToken(jwt, all);
  }
}
