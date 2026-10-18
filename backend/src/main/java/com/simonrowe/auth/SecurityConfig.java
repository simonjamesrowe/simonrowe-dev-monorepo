package com.simonrowe.auth;

import com.simonrowe.coparent.config.CoparentFeatureFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  static final String ADMIN_ROLE = "DEV_PORTAL_ADMIN";
  /** Opens the meals app and nothing else, for someone who should not be a site admin. */
  static final String MEALS_ROLE = "MEALS_USER";

  // java:S4502: CSRF protection guards credentials the browser attaches by itself (a session
  // cookie, HTTP basic). This chain authenticates only a bearer JWT read from the
  // Authorization header and creates no session, so a cross-site request carries nothing
  // to forge with.
  @SuppressWarnings("java:S4502")
  @Bean
  public SecurityFilterChain filterChain(
      final HttpSecurity http,
      final RolesJwtAuthenticationConverter rolesConverter,
      final CoparentFeatureFilter coparentFeatureFilter
  ) throws Exception {
    http
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/admin/**").hasRole(ADMIN_ROLE)
            // Meals holds recipes from copyrighted ebooks: every route, images included, is
            // private (spec 053). MEALS_USER opens it without making someone a site admin;
            // kitchen, Up next, shopping and ratings are per person, keyed on the subject.
            // Loading the books stays with the owner, who bought them. Order matters: the
            // narrower admin path must come before the meals-wide rule that would match it.
            .requestMatchers("/api/meals/admin/**").hasRole(ADMIN_ROLE)
            .requestMatchers("/api/meals/**").hasAnyRole(ADMIN_ROLE, MEALS_ROLE)
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
            // The playlist is shared like favourites: anyone can see it and play it, and
            // only a session can change it. Its "make audio" also spends the TTS budget.
            .requestMatchers(HttpMethod.PUT, "/api/playlist/**").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/playlist/**").authenticated()
            .requestMatchers(HttpMethod.DELETE, "/api/playlist/**").authenticated()
            .requestMatchers("/api/coparent/**").authenticated()
            .anyRequest().permitAll()
        )
        .headers(headers -> headers.cacheControl(cache -> cache.disable()))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
            jwt.jwtAuthenticationConverter(rolesConverter)
        ))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        )
        .addFilterBefore(coparentFeatureFilter, BearerTokenAuthenticationFilter.class);
    return http.build();
  }
}
