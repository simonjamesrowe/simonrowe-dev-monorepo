package com.simonrowe.meals;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security for the private meals MCP server at {@code /api/meals/mcp}: its own filter chain,
 * ordered before the main one, that accepts a meals personal access token and nothing else.
 *
 * <p>It is a separate chain because the main chain's OAuth2 resource server would try to decode
 * the personal token as a JWT and reject it. Everything else under {@code /api/meals/**} stays on
 * the main chain and its Auth0 admin role.
 */
@Configuration
public class MealsMcpSecurity {

  public static final String PATH = "/api/meals/mcp";
  static final String OWNER_ATTRIBUTE = "meals.mcp.owner";

  // java:S4502: see the CSRF comment in the chain below.
  @SuppressWarnings("java:S4502")
  @Bean
  @Order(1)
  public SecurityFilterChain mealsMcpFilterChain(final HttpSecurity http,
      final McpTokenService tokens) throws Exception {
    http
        .securityMatcher(PATH, PATH + "/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .addFilterBefore(new TokenFilter(tokens), AnonymousAuthenticationFilter.class)
        .exceptionHandling(e -> e.authenticationEntryPoint(
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .headers(headers -> headers.cacheControl(cache -> cache.disable()))
        // CSRF needs a credential the browser attaches by itself. This chain reads only the
        // Authorization header and creates no session or cookie, so a cross-site request
        // carries nothing to forge with: the same reasoning as SecurityConfig's API chain.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  /** Authenticates a {@code Bearer meals_...} token as its owner, with the admin role. */
  static final class TokenFilter extends OncePerRequestFilter {

    private final McpTokenService tokens;

    TokenFilter(final McpTokenService tokens) {
      this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
        final HttpServletResponse response, final FilterChain chain)
        throws ServletException, IOException {
      String header = request.getHeader("Authorization");
      if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
        tokens.authenticate(header.substring(7).strip()).ifPresent(token -> {
          request.setAttribute(OWNER_ATTRIBUTE, token.ownerId());
          SecurityContextHolder.getContext().setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(token.ownerId(), null,
                  List.of(new SimpleGrantedAuthority("ROLE_DEV_PORTAL_ADMIN"))));
        });
      }
      chain.doFilter(request, response);
    }
  }
}
