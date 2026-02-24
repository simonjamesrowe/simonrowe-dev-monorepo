package com.simonrowe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Value("${cors.allowed-origins:}")
  private String allowedOrigins;

  @Value("${uploads.path:uploads/}")
  private String uploadsPath;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (!allowedOrigins.isBlank()) {
      registry.addMapping("/**")
          .allowedOrigins(allowedOrigins.split(","))
          .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/uploads/**")
        .addResourceLocations("file:" + uploadsPath);
  }
}
