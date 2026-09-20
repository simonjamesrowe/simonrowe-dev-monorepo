package com.simonrowe.coparent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fails CoParent requests closed while leaving every other product untouched. */
@Component
public class CoparentFeatureFilter extends OncePerRequestFilter {

  private static final String API_PREFIX = "/api/coparent";

  private final boolean enabled;

  public CoparentFeatureFilter(@Value("${coparent.enabled:false}") final boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  protected boolean shouldNotFilter(final HttpServletRequest request) {
    return !request.getRequestURI().startsWith(API_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain) throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store");
    if (enabled) {
      filterChain.doFilter(request, response);
    } else {
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(
          "{\"code\":\"coparent_disabled\",\"message\":\"CoParent is unavailable\"}");
    }
  }
}
