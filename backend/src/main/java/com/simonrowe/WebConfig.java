package com.simonrowe;

import com.simonrowe.ratelimit.RateLimitConfig;
import com.simonrowe.ratelimit.RateLimitInterceptor;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RateLimitConfig.class)
public class WebConfig implements WebMvcConfigurer {

  @Value("${cors.allowed-origins:}")
  private String allowedOrigins;

  @Value("${uploads.path:backend/uploads/}")
  private String uploadsPath;

  private final RateLimitInterceptor rateLimitInterceptor;

  public WebConfig(final RateLimitInterceptor rateLimitInterceptor) {
    this.rateLimitInterceptor = rateLimitInterceptor;
  }

  @Override
  public void addCorsMappings(final CorsRegistry registry) {
    if (!allowedOrigins.isBlank()) {
      registry.addMapping("/**")
          .allowedOrigins(allowedOrigins.split(","))
          .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
  }

  @Override
  public void addResourceHandlers(final ResourceHandlerRegistry registry) {
    String location = Path.of(uploadsPath).toAbsolutePath().toUri().toString();
    registry.addResourceHandler("/uploads/**")
        .addResourceLocations(location);
  }

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry.addInterceptor(rateLimitInterceptor)
        .addPathPatterns("/mcp/**");
  }
}
