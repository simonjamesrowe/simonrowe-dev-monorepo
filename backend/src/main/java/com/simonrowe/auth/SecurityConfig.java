package com.simonrowe.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  static final String ADMIN_ROLE = "DEV_PORTAL_ADMIN";

  @Bean
  public SecurityFilterChain filterChain(
      final HttpSecurity http,
      final RolesJwtAuthenticationConverter rolesConverter
  ) throws Exception {
    http
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/admin/**").hasRole(ADMIN_ROLE)
            // Favourites are globally shared: reads are public, only writes need a session.
            .requestMatchers(HttpMethod.PUT, "/api/favourites/**").authenticated()
            .requestMatchers(HttpMethod.DELETE, "/api/favourites/**").authenticated()
            // Article summaries are globally shared too: reads are public, and only the
            // two writes need a session because only they cost money — an LLM call and,
            // more expensively, a text-to-speech render against a monthly character
            // budget. Any valid JWT suffices; these are not admin-role gated.
            //
            // Note the asymmetry with /api/blogs/*/narration, whose POST is public. That
            // endpoint's contract predates the budget concern and is deliberately left
            // alone; the new surface does not inherit it.
            .requestMatchers(HttpMethod.POST, "/api/news/*/summary").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/news/*/summary/narration")
            .authenticated()
            .anyRequest().permitAll()
        )
        .headers(headers -> headers.cacheControl(cache -> cache.disable()))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
            jwt.jwtAuthenticationConverter(rolesConverter)
        ))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );
    return http.build();
  }
}
