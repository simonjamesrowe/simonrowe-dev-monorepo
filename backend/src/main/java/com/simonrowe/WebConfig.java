package com.simonrowe;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Value("${cors.allowed-origins:}")
  private String allowedOrigins;

  @Value("${uploads.path:backend/uploads/}")
  private String uploadsPath;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (!allowedOrigins.isBlank()) {
      registry.addMapping("/**")
          .allowedOrigins(allowedOrigins.split(","))
          .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String location = Path.of(uploadsPath).toAbsolutePath().toUri().toString();
    registry.addResourceHandler("/uploads/**")
        .addResourceLocations(location);
  }
}
