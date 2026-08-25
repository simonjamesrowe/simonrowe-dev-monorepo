package com.simonrowe;

import com.simonrowe.dataops.PlatformBackupProperties;
import com.simonrowe.dataops.RedeployProperties;
import com.simonrowe.ratelimit.RateLimitConfig;
import com.simonrowe.ratelimit.RateLimitInterceptor;
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
@EnableConfigurationProperties({
    RateLimitConfig.class,
    RedeployProperties.class,
    PlatformBackupProperties.class})
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

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry.addInterceptor(rateLimitInterceptor)
        .addPathPatterns("/mcp/**", "/api/blogs/*/narration");
  }
}
