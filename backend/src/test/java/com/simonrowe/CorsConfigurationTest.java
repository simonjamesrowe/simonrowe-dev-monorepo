package com.simonrowe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Guards the local multi-entry frontend origins declared in the shipped configuration. */
class CorsConfigurationTest {

  @Test
  void localDefaultAllowsTheCoparentHostname() throws IOException {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"));

    Object configured = sources.stream()
        .map(source -> source.getProperty("cors.allowed-origins"))
        .filter(value -> value != null)
        .findFirst()
        .orElseThrow();

    assertThat(configured.toString())
        .as("proxied CoParent POST requests retain this Origin and Spring rejects them otherwise")
        .contains("http://coparents.localhost:5173");
  }
}
