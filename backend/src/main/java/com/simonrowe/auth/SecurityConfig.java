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
            // Article summaries and narrations are globally shared too: reads are public,
            // and only the writes need a session because only they cost money — an LLM
            // call and, more expensively, a text-to-speech render against a monthly
            // character budget. Any valid JWT suffices; these are not admin-role gated.
            //
            // The blog narration POST was public until the listing pages gained a Listen
            // control on every card (035-listen-from-listing). It draws on the same
            // monthly TTS budget as summary narration, so gating only the new surface
            // would have left the identical post anonymously narratable from its detail
            // page — the budget still drainable, just from a different URL. All three
            // writes are now gated alike. GET stays public on all of them: the audio is
            // shared content, not per-reader state, and a signed-out reader has to be able
            // to see a duration on a card and press play.
            .requestMatchers(HttpMethod.POST, "/api/news/*/summary").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/news/*/summary/narration")
            .authenticated()
            .requestMatchers(HttpMethod.POST, "/api/blogs/*/narration").authenticated()
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
