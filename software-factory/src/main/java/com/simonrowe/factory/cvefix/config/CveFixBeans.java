package com.simonrowe.factory.cvefix.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes nested configuration records as beans so components can depend on them directly. */
@Configuration
public class CveFixBeans {

  /**
   * Extracts the Dependency-Track slice of {@link CveFixProperties} as its own bean.
   *
   * @param properties the bound {@code factory.cvefix} configuration
   * @return the {@code CveFixProperties.DependencyTrack} slice, for {@code DependencyTrackClient}
   */
  @Bean
  public CveFixProperties.DependencyTrack dependencyTrackProperties(
      final CveFixProperties properties) {
    return properties.dependencyTrack();
  }
}
