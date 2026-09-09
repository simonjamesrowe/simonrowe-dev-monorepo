package com.simonrowe;

import com.simonrowe.ratelimit.RateLimitConfig;
import com.simonrowe.ratelimit.RateLimitInterceptor;
import com.simonrowe.shortlink.ShortLinkProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.CacheControl;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({RateLimitConfig.class, ShortLinkProperties.class})
public class WebConfig implements WebMvcConfigurer {

  @Value("${cors.allowed-origins:}")
  private String allowedOrigins;

  @Value("${uploads.path:backend/uploads/}")
  private String uploadsPath;

  private final RateLimitInterceptor rateLimitInterceptor;

  public WebConfig(final RateLimitInterceptor rateLimitInterceptor) {
    this.rateLimitInterceptor = rateLimitInterceptor;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    final CorsConfiguration config = new CorsConfiguration();
    if (!allowedOrigins.isBlank()) {
      config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
    }
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  @Override
  public void addResourceHandlers(final ResourceHandlerRegistry registry) {
    String location = Path.of(uploadsPath).toAbsolutePath().toUri().toString();
    registry.addResourceHandler("/uploads/**")
        .addResourceLocations(location)
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365))
            .cachePublic()
            .immutable());
  }

  /**
   * Registers rate limiting on an explicit allowlist rather than globally.
   *
   * <p>{@code /s/**} is deliberately absent. A single paste of a share link into a busy
   * Slack workspace produces a burst of unfurl fetches from one address range before any
   * human clicks it, and LinkedIn, WhatsApp and iMessage behave the same way. A 429 there
   * does not throttle an abuser, it breaks the preview — which is the whole point of the
   * endpoint. The path costs a primary-key lookup and a string build, with no model call
   * and no external I/O, so there is nothing expensive to protect.
   *
   * @param registry the interceptor registry
   */
  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry.addInterceptor(rateLimitInterceptor)
        .addPathPatterns("/mcp/**", "/api/blogs/*/narration",
            "/api/news/*/summary", "/api/news/*/summary/narration",
            // Term Time's chat turn is unauthenticated and costs a model call, so it is the
            // one endpoint here that anyone on the internet can spend money on without so
            // much as a login. Rate limiting bounds one caller; SchoolBudget bounds the day.
            // Both are needed - a limiter alone still permits a botnet, and a daily ceiling
            // alone lets one script exhaust everyone else's allowance before breakfast.
            //
            // /api/school/config is deliberately NOT listed: the page fetches it on load, so
            // limiting it would 429 the year selector rather than the expensive call.
            "/api/school/chat");
  }
}
